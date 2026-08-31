package middleware.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import middleware.audit.AuditService;
import middleware.storage.Store;

/**
 * Self-service password reset akisinin domain servisi.
 *
 * Sorumluluklari:
 * - reset kodu uretmek
 * - kodu plaintext yerine SHA-256 hash olarak saklamak
 * - token expiration kontrolu
 * - hatali deneme sayisini takip etmek
 * - 3 hatali denemeden sonra challenge'i kilitlemek
 * - super admin approval sonrasinda yeni challenge uretmek
 * - basarili sifre sifirlamayi gerceklestirmek
 *
 * NOT:
 * Bu servis email gondermez.
 * Development asamasinda uretilen kod console'a yazdirilabilir.
 * Daha sonra email delivery ayri bir servis olarak eklenecek.
 */
public class PasswordResetService {

    public static final String COLLECTION =
            "__meta__/password_reset_tokens";

    private static final int TOKEN_TTL_MINUTES = 15;
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private final Store store;
    private final AuthService authService;
    private final AuditService auditService;

    public PasswordResetService(
            Store store,
            AuthService authService,
            AuditService auditService
    ) {
        this.store = store;
        this.authService = authService;
        this.auditService = auditService;
    }

    // ============================================================
    // RESET REQUEST
    // ============================================================

    /**
     * Kullanici icin yeni password reset challenge'i olusturur.
     *
     * Kullanici yoksa null doner.
     *
     * Controller / Router katmani kullanici bulunup bulunmadigini
     * istemciye aciklamamalidir. Response her durumda generic olmalidir.
     */
    public String requestReset(String email) {

        if (email == null || email.isBlank()) {
            return null;
        }

        String normalizedEmail =
                email.trim();

        User user =
                authService.byEmail(normalizedEmail);

        if (user == null
                || !user.active()
                || user.deleted()) {

            return null;
        }

        /*
         * Kullanici admin approval nedeniyle kilitliyse
         * yeni kod uretme.
         */
        if (requiresAdminApproval(user.id())) {
            return null;
        }

        /*
         * Onceki aktif challenge'lari iptal et.
         */
        revokeActiveChallenges(user.id());

        String resetCode =
                generateResetCode();

        Map<String, Object> challenge =
                new LinkedHashMap<>();

        challenge.put(
                "userId",
                user.id()
        );

        challenge.put(
                "email",
                user.email()
        );

        challenge.put(
                "tokenHash",
                sha256(resetCode)
        );

        challenge.put(
                "expiresAt",
                now()
                        .plusMinutes(TOKEN_TTL_MINUTES)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );

        challenge.put(
                "failedAttempts",
                0
        );

        challenge.put(
                "locked",
                false
        );

        challenge.put(
                "requiresAdminApproval",
                false
        );

        challenge.put(
                "revoked",
                false
        );

        challenge.put(
                "used",
                false
        );

        challenge.put(
                "usedAt",
                null
        );

        store.insert(
                COLLECTION,
                challenge
        );

        auditService.record(
                "PASSWORD_RESET_REQUESTED",
                user.id(),
                user.name(),
                user.id(),
                user.name(),
                user.name() + " kullanicisi sifre degisikligi talep etti.",
                Map.of(),
                Map.of(
                        "email",
                        user.email()
                ),
                false
        );

        /*
         * DEVELOPMENT ONLY.
         *
         * Email servisi eklenene kadar reset kodunu
         * burada gorebiliriz.
         */
        System.out.println(
                "[password-reset] DEV reset code for "
                        + user.email()
                        + ": "
                        + resetCode
        );

        return resetCode;
    }

    // ============================================================
    // RESET CONFIRMATION
    // ============================================================

    /**
     * Reset kodunu dogrular ve yeni sifreyi kaydeder.
     */
    public ResetResult confirmReset(
            String email,
            String resetCode,
            String newPassword
    ) {

        if (email == null
                || resetCode == null
                || newPassword == null) {

            return ResetResult.INVALID;
        }

        User user =
                authService.byEmail(
                        email.trim()
                );

        if (user == null) {
            return ResetResult.INVALID;
        }

        Map<String, Object> challenge =
                findLatestChallenge(
                        user.id()
                );

        if (challenge == null) {
            return ResetResult.INVALID;
        }

        if (Boolean.TRUE.equals(
                challenge.get("revoked")
        )) {
            return ResetResult.INVALID;
        }

        if (Boolean.TRUE.equals(
                challenge.get("used")
        )) {
            return ResetResult.INVALID;
        }

        if (Boolean.TRUE.equals(
                challenge.get("locked")
        )) {
            return ResetResult.ADMIN_APPROVAL_REQUIRED;
        }

        if (isExpired(challenge)) {
            return ResetResult.EXPIRED;
        }

        String expectedHash =
                String.valueOf(
                        challenge.get("tokenHash")
                );

        String actualHash =
                sha256(
                        resetCode.trim()
                );

        if (!constantTimeEquals(
                expectedHash,
                actualHash
        )) {

            return registerFailedAttempt(
                    challenge,
                    user
            );
        }

        if (!validPassword(newPassword)) {
            return ResetResult.WEAK_PASSWORD;
        }

        /*
         * Mevcut AuthService resetPassword() metodunu
         * kullaniyoruz.
         *
         * Password hashing daha sonra authentication
         * security task'inda ele alinacak.
         */
        String updatedPassword =
                authService.resetPassword(
                        user.id(),
                        newPassword
                );

        if (updatedPassword == null) {
                return ResetResult.INVALID;
        }

        Map<String, Object> patch =
                new LinkedHashMap<>();

        patch.put(
                "used",
                true
        );

        patch.put(
                "usedAt",
                now().format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                )
        );

        store.updateById(
                COLLECTION,
                String.valueOf(
                        challenge.get("id")
                ),
                patch
        );

        /*
         * Basarili reset sonrasinda kullaniciya ait
         * diger challenge'lari da iptal et.
         */
        revokeOtherChallenges(
                user.id(),
                String.valueOf(
                        challenge.get("id")
                )
        );

        auditService.record(
                "PASSWORD_RESET_CONFIRMED",
                user.id(),
                user.name(),
                user.id(),
                user.name(),
                user.name() + " kullanicisi sifresini degistirdi.",
                Map.of(),
                Map.of(
                        "email",
                        user.email()
                ),
                false
        );

        return ResetResult.SUCCESS;
    }

    // ============================================================
    // ADMIN APPROVAL
    // ============================================================

    /**
     * Super admin tarafindan kilitli password reset akisina
     * yeniden izin verir.
     *
     * Eski kod tekrar aktif edilmez.
     * Tamamen yeni reset kodu uretilir.
     */
    public String approveReset(
            String userId,
            User approvedBy
    ) {

        if (userId == null
                || approvedBy == null
                || !approvedBy.isSuperAdmin()) {

            return null;
        }

        User target =
                authService.byId(
                        userId
                );

        if (target == null) {
            return null;
        }

        Map<String, Object> challenge =
                findLatestChallenge(
                        target.id()
                );

        if (challenge == null
                || !Boolean.TRUE.equals(
                        challenge.get(
                                "requiresAdminApproval"
                        )
                )) {

            return null;
        }

        /*
         * Eski challenge tamamen iptal edilir.
         */
        Map<String, Object> revokePatch =
                new LinkedHashMap<>();

        revokePatch.put(
                "revoked",
                true
        );

        revokePatch.put(
                "requiresAdminApproval",
                false
        );

        store.updateById(
                COLLECTION,
                String.valueOf(
                        challenge.get("id")
                ),
                revokePatch
        );

        String newCode =
                createApprovedChallenge(
                        target
                );

        auditService.record(
                "PASSWORD_RESET_APPROVED",
                approvedBy.id(),
                approvedBy.name(),
                target.id(),
                target.name(),
                target.name() + " kullanicisinin sifre degisikligi talebi Super Admin tarafindan onaylandi.",
                Map.of(),
                Map.of(
                        "email",
                        target.email()
                ),
                false
        );

        System.out.println(
                "[password-reset] DEV approved reset code for "
                        + target.email()
                        + ": "
                        + newCode
        );

        return newCode;
    }

    // ============================================================
    // FAILED ATTEMPTS
    // ============================================================

    private ResetResult registerFailedAttempt(
            Map<String, Object> challenge,
            User user
    ) {

        int failedAttempts =
                intValue(
                        challenge.get(
                                "failedAttempts"
                        )
                ) + 1;

        Map<String, Object> patch =
                new LinkedHashMap<>();

        patch.put(
                "failedAttempts",
                failedAttempts
        );

        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {

            patch.put(
                    "locked",
                    true
            );

            patch.put(
                    "requiresAdminApproval",
                    true
            );

            store.updateById(
                    COLLECTION,
                    String.valueOf(
                            challenge.get("id")
                    ),
                    patch
            );

            auditService.record(
                    "PASSWORD_RESET_APPROVAL_REQUIRED",
                    user.id(),
                    user.name(),
                    user.id(),
                    user.name(),
                    user.name()
                            + " kullanicisinin sifre degisikligi "
                            + failedAttempts
                            + " hatali deneme sonrasi Super Admin onayi bekliyor.",
                    Map.of(),
                    Map.of(
                            "email",
                            user.email(),
                            "failedAttempts",
                            failedAttempts
                    ),
                    false
            );

            return ResetResult.ADMIN_APPROVAL_REQUIRED;
        }

        store.updateById(
                COLLECTION,
                String.valueOf(
                        challenge.get("id")
                ),
                patch
        );

        auditService.record(
                "passwordResetFailed",
                user.id(),
                user.name(),
                user.id(),
                user.name(),
                "Invalid password reset code.",
                null,
                Map.of(
                        "failedAttempts",
                        failedAttempts
                ),
                false
        );

        return ResetResult.INVALID;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private String createApprovedChallenge(
            User user
    ) {

        String resetCode =
                generateResetCode();

        Map<String, Object> challenge =
                new LinkedHashMap<>();

        challenge.put(
                "userId",
                user.id()
        );

        challenge.put(
                "email",
                user.email()
        );

        challenge.put(
                "tokenHash",
                sha256(resetCode)
        );

        challenge.put(
                "expiresAt",
                now()
                        .plusMinutes(TOKEN_TTL_MINUTES)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );

        challenge.put(
                "failedAttempts",
                0
        );

        challenge.put(
                "locked",
                false
        );

        challenge.put(
                "requiresAdminApproval",
                false
        );

        challenge.put(
                "revoked",
                false
        );

        challenge.put(
                "used",
                false
        );

        challenge.put(
                "usedAt",
                null
        );

        store.insert(
                COLLECTION,
                challenge
        );

        return resetCode;
    }

    private Map<String, Object> findLatestChallenge(
            String userId
    ) {

        List<Map<String, Object>> challenges =
                store.find(
                        COLLECTION,
                        Map.of(
                                "userId",
                                userId
                        )
                );

        Map<String, Object> latest =
                null;

        for (Map<String, Object> challenge
                : challenges) {

            if (latest == null) {
                latest = challenge;
                continue;
            }

            String currentCreated =
                    String.valueOf(
                            challenge.getOrDefault(
                                    "createdAt",
                                    ""
                            )
                    );

            String latestCreated =
                    String.valueOf(
                            latest.getOrDefault(
                                    "createdAt",
                                    ""
                            )
                    );

            if (currentCreated.compareTo(
                    latestCreated
            ) > 0) {

                latest = challenge;
            }
        }

        return latest;
    }

    private boolean requiresAdminApproval(
            String userId
    ) {

        Map<String, Object> challenge =
                findLatestChallenge(
                        userId
                );

        return challenge != null
                && Boolean.TRUE.equals(
                        challenge.get(
                                "requiresAdminApproval"
                        )
                )
                && !Boolean.TRUE.equals(
                        challenge.get("revoked")
                );
    }

    private void revokeActiveChallenges(
            String userId
    ) {

        List<Map<String, Object>> challenges =
                store.find(
                        COLLECTION,
                        Map.of(
                                "userId",
                                userId
                        )
                );

        for (Map<String, Object> challenge
                : challenges) {

            if (Boolean.TRUE.equals(
                    challenge.get("used")
            )) {
                continue;
            }

            if (Boolean.TRUE.equals(
                    challenge.get("revoked")
            )) {
                continue;
            }

            store.updateById(
                    COLLECTION,
                    String.valueOf(
                            challenge.get("id")
                    ),
                    Map.of(
                            "revoked",
                            true
                    )
            );
        }
    }

    private void revokeOtherChallenges(
            String userId,
            String exceptId
    ) {

        List<Map<String, Object>> challenges =
                store.find(
                        COLLECTION,
                        Map.of(
                                "userId",
                                userId
                        )
                );

        for (Map<String, Object> challenge
                : challenges) {

            String id =
                    String.valueOf(
                            challenge.get("id")
                    );

            if (id.equals(exceptId)) {
                continue;
            }

            if (Boolean.TRUE.equals(
                    challenge.get("revoked")
            )) {
                continue;
            }

            store.updateById(
                    COLLECTION,
                    id,
                    Map.of(
                            "revoked",
                            true
                    )
            );
        }
    }

    private static boolean isExpired(
            Map<String, Object> challenge
    ) {

        try {

            String expiresAt =
                    String.valueOf(
                            challenge.get(
                                    "expiresAt"
                            )
                    );

            LocalDateTime expiry =
                    LocalDateTime.parse(
                            expiresAt,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    );

            return now().isAfter(
                    expiry
            );

        } catch (Exception e) {

            return true;
        }
    }

    private static boolean validPassword(
            String password
    ) {

        if (password == null
                || password.length() < 8) {

            return false;
        }

        boolean upper = false;
        boolean lower = false;
        boolean digit = false;

        for (char c : password.toCharArray()) {

            if (Character.isUpperCase(c)) {
                upper = true;
            }

            if (Character.isLowerCase(c)) {
                lower = true;
            }

            if (Character.isDigit(c)) {
                digit = true;
            }
        }

        return upper
                && lower
                && digit;
    }

    private static String generateResetCode() {

        /*
         * Kullanici tarafinda kolay kopyalanabilir,
         * yeterince uzun development reset code.
         *
         * Daha sonra email/deep-link modelinde bu kisim
         * 256-bit URL-safe token'a cevrilebilir.
         */

        byte[] bytes =
                new byte[16];

        RANDOM.nextBytes(
                bytes
        );

        return HexFormat
                .of()
                .formatHex(bytes)
                .toUpperCase();
    }

    private static String sha256(
            String value
    ) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat
                    .of()
                    .formatHex(hash);

        } catch (Exception e) {

            throw new IllegalStateException(
                    "SHA-256 kullanilamiyor.",
                    e
            );
        }
    }

    private static boolean constantTimeEquals(
            String expected,
            String actual
    ) {

        if (expected == null
                || actual == null) {

            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(
                        StandardCharsets.UTF_8
                ),
                actual.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    private static int intValue(
            Object value
    ) {

        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(
                    String.valueOf(value)
            );
        } catch (Exception e) {
            return 0;
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    // ============================================================
    // RESULT
    // ============================================================

    public enum ResetResult {

        SUCCESS,

        /**
         * Kod bulunamadi veya yanlis.
         */
        INVALID,

        /**
         * Kodun 15 dakikalik suresi dolmus.
         */
        EXPIRED,

        /**
         * Yeni sifre policy'yi karsilamiyor.
         */
        WEAK_PASSWORD,

        /**
         * 3 hatali deneme sonrasi Super Admin onayi gerekli.
         */
        ADMIN_APPROVAL_REQUIRED
    }
}

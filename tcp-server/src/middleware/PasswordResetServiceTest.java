package middleware;

import java.util.Map;
import java.util.Set;

import middleware.audit.AuditService;
import middleware.auth.AuthService;
import middleware.auth.PasswordResetService;
import middleware.auth.PasswordResetService.ResetResult;
import middleware.auth.User;
import middleware.storage.InMemoryStore;
import middleware.storage.Store;

public class PasswordResetServiceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {

        testResetRequestCreatesChallenge();
        testWrongCode();
        testThreeWrongAttemptsRequireAdmin();
        testAdminApprovalCreatesNewCode();
        testSuccessfulReset();
        testOldCodeCannotBeReused();

        System.out.println();
        System.out.println(
                "PasswordResetServiceTest: "
                        + passed
                        + " passed, "
                        + failed
                        + " failed"
        );

        if (failed > 0) {
            System.exit(1);
        }
    }

    // ============================================================
    // TEST 1
    // ============================================================

    private static void testResetRequestCreatesChallenge() {

        Fixture f = fixture();

        String code =
                f.reset.requestReset(
                        "user@test.com"
                );

        check(
                code != null
                        && !code.isBlank(),
                "reset request should generate code"
        );

        var rows =
                f.store.find(
                        PasswordResetService.COLLECTION,
                        Map.of(
                                "userId",
                                f.user.id()
                        )
                );

        check(
                rows.size() == 1,
                "reset challenge should be stored"
        );

        Map<String, Object> challenge =
                rows.get(0);

        check(
                challenge.get("tokenHash") != null,
                "token hash should be stored"
        );

        check(
                !code.equals(
                        challenge.get("tokenHash")
                ),
                "plaintext reset code must not be stored"
        );

        check(
                Integer.valueOf(0).equals(
                        challenge.get("failedAttempts")
                ),
                "failed attempts should start at zero"
        );
    }

    // ============================================================
    // TEST 2
    // ============================================================

    private static void testWrongCode() {

        Fixture f = fixture();

        f.reset.requestReset(
                "user@test.com"
        );

        ResetResult result =
                f.reset.confirmReset(
                        "user@test.com",
                        "WRONG-CODE",
                        "NewPassword123"
                );

        check(
                result == ResetResult.INVALID,
                "wrong code should return INVALID"
        );

        Map<String, Object> challenge =
                latestChallenge(f);

        check(
                Integer.valueOf(1).equals(
                        challenge.get(
                                "failedAttempts"
                        )
                ),
                "wrong code should increment failed attempts"
        );
    }

    // ============================================================
    // TEST 3
    // ============================================================

    private static void testThreeWrongAttemptsRequireAdmin() {

        Fixture f = fixture();

        f.reset.requestReset(
                "user@test.com"
        );

        ResetResult r1 =
                f.reset.confirmReset(
                        "user@test.com",
                        "WRONG-1",
                        "NewPassword123"
                );

        ResetResult r2 =
                f.reset.confirmReset(
                        "user@test.com",
                        "WRONG-2",
                        "NewPassword123"
                );

        ResetResult r3 =
                f.reset.confirmReset(
                        "user@test.com",
                        "WRONG-3",
                        "NewPassword123"
                );

        check(
                r1 == ResetResult.INVALID,
                "first wrong attempt should be INVALID"
        );

        check(
                r2 == ResetResult.INVALID,
                "second wrong attempt should be INVALID"
        );

        check(
                r3
                        == ResetResult.ADMIN_APPROVAL_REQUIRED,
                "third wrong attempt should require admin"
        );

        Map<String, Object> challenge =
                latestChallenge(f);

        check(
                Boolean.TRUE.equals(
                        challenge.get("locked")
                ),
                "challenge should be locked"
        );

        check(
                Boolean.TRUE.equals(
                        challenge.get(
                                "requiresAdminApproval"
                        )
                ),
                "challenge should require admin approval"
        );
    }

    // ============================================================
    // TEST 4
    // ============================================================

    private static void testAdminApprovalCreatesNewCode() {

        Fixture f = fixture();

        String oldCode =
                f.reset.requestReset(
                        "user@test.com"
                );

        for (int i = 0; i < 3; i++) {

            f.reset.confirmReset(
                    "user@test.com",
                    "WRONG-" + i,
                    "NewPassword123"
            );
        }

        String newCode =
                f.reset.approveReset(
                        f.user.id(),
                        f.admin
                );

        check(
                newCode != null
                        && !newCode.isBlank(),
                "admin approval should generate new code"
        );

        check(
                !newCode.equals(oldCode),
                "admin approval must not reactivate old code"
        );

        var rows =
                f.store.find(
                        PasswordResetService.COLLECTION,
                        Map.of(
                                "userId",
                                f.user.id()
                        )
                );

        check(
                rows.size() == 2,
                "admin approval should create second challenge"
        );
    }

    // ============================================================
    // TEST 5
    // ============================================================

    private static void testSuccessfulReset() {

        Fixture f = fixture();

        String code =
                f.reset.requestReset(
                        "user@test.com"
                );

        ResetResult result =
                f.reset.confirmReset(
                        "user@test.com",
                        code,
                        "NewPassword123"
                );

        check(
                result == ResetResult.SUCCESS,
                "valid reset should succeed"
        );

        User authenticated =
                f.auth.authenticate(
                        "user@test.com",
                        "NewPassword123"
                );

        check(
                authenticated != null,
                "new password should authenticate"
        );

        User oldPassword =
                f.auth.authenticate(
                        "user@test.com",
                        "OldPassword123"
                );

        check(
                oldPassword == null,
                "old password should no longer work"
        );

        Map<String, Object> challenge =
                latestChallenge(f);

        check(
                Boolean.TRUE.equals(
                        challenge.get("used")
                ),
                "successful reset should mark challenge used"
        );
    }

    // ============================================================
    // TEST 6
    // ============================================================

    private static void testOldCodeCannotBeReused() {

        Fixture f = fixture();

        String code =
                f.reset.requestReset(
                        "user@test.com"
                );

        ResetResult first =
                f.reset.confirmReset(
                        "user@test.com",
                        code,
                        "NewPassword123"
                );

        ResetResult second =
                f.reset.confirmReset(
                        "user@test.com",
                        code,
                        "OtherPassword123"
                );

        check(
                first == ResetResult.SUCCESS,
                "first reset should succeed"
        );

        check(
                second == ResetResult.INVALID,
                "used reset code must not work twice"
        );
    }

    // ============================================================
    // FIXTURE
    // ============================================================

    private static Fixture fixture() {

        Store store =
                new InMemoryStore();

        AuthService auth =
                new AuthService(store);

        AuditService audit =
                new AuditService(store);

        User admin =
                new User(
                        auth.newUserId(),
                        "Super Admin",
                        "admin@test.com",
                        "AdminPassword123",
                        "superAdmin",
                        Set.of(),
                        Set.of()
                );

        User user =
                new User(
                        auth.newUserId(),
                        "Test User",
                        "user@test.com",
                        "OldPassword123",
                        "user",
                        Set.of(),
                        Set.of()
                );

        auth.createUser(admin);
        auth.createUser(user);

        PasswordResetService reset =
                new PasswordResetService(
                        store,
                        auth,
                        audit
                );

        return new Fixture(
                store,
                auth,
                reset,
                admin,
                user
        );
    }

    private static Map<String, Object> latestChallenge(
            Fixture f
    ) {

        var rows =
                f.store.find(
                        PasswordResetService.COLLECTION,
                        Map.of(
                                "userId",
                                f.user.id()
                        )
                );

        if (rows.isEmpty()) {
            throw new AssertionError(
                    "No reset challenge found"
            );
        }

        return rows.get(
                rows.size() - 1
        );
    }

    private static void check(
            boolean condition,
            String message
    ) {

        if (condition) {

            passed++;

            System.out.println(
                    "[PASS] "
                            + message
            );

        } else {

            failed++;

            System.out.println(
                    "[FAIL] "
                            + message
            );
        }
    }

    private record Fixture(
            Store store,
            AuthService auth,
            PasswordResetService reset,
            User admin,
            User user
    ) {
    }
}
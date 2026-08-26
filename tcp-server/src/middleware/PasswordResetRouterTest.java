package middleware;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import java.io.PrintWriter;
import java.io.StringWriter;

import jsonparser.Json;
import middleware.server.ClientSession;
import middleware.audit.AuditService;
import middleware.auth.AuthService;
import middleware.auth.User;
import middleware.events.EventBus;
import middleware.file.RequestFileService;
import middleware.protocol.Router;
import middleware.storage.InMemoryStore;
import middleware.storage.Store;

public class PasswordResetRouterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {

        testRequestPasswordResetIsAnonymous();
        testUnknownEmailStillReturnsGenericSuccess();
        testConfirmPasswordResetWithWrongCode();
        testThreeWrongAttemptsRequireAdminApproval();
        testNormalUserCannotApproveReset();
        testSuperAdminCanApproveReset();

        System.out.println();
        System.out.println(
                "PasswordResetRouterTest: "
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
    // REQUEST_PASSWORD_RESET anonymous olmali
    // ============================================================

    private static void testRequestPasswordResetIsAnonymous() {

        Fixture f = fixture();

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                "reset-request-1"
        );

        request.put(
                "action",
                "REQUEST_PASSWORD_RESET"
        );

        request.put(
                "document",
                Map.of(
                        "email",
                        "user@test.com"
                )
        );

        /*
         * Dikkat:
         * username/password bilerek YOK.
         */
        String response =
                f.router.handle(
                    Json.stringify(request),
                    f.session
                );

        check(
                response != null,
                "REQUEST_PASSWORD_RESET should return response"
        );

        check(
                response.contains("\"status\":\"OK\"")
                        || response.contains("\"status\": \"OK\""),
                "REQUEST_PASSWORD_RESET should be anonymous and return OK"
        );

        check(
                response.contains(
                        "If an account exists for this email"
                ),
                "reset request should return generic message"
        );
    }

    // ============================================================
    // TEST 2
    // Kullanici enumeration engellenmeli
    // ============================================================

    private static void testUnknownEmailStillReturnsGenericSuccess() {

        Fixture f = fixture();

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                "reset-request-unknown"
        );

        request.put(
                "action",
                "REQUEST_PASSWORD_RESET"
        );

        request.put(
                "document",
                Map.of(
                        "email",
                        "does-not-exist@test.com"
                )
        );

        String response =
                f.router.handle(
                        Json.stringify(request),
                        f.session
                );

        check(
                response.contains("\"status\":\"OK\"")
                        || response.contains("\"status\": \"OK\""),
                "unknown email should still return OK"
        );

        check(
                !response.toLowerCase().contains(
                        "user not found"
                ),
                "response must not reveal that account does not exist"
        );
    }

    // ============================================================
    // TEST 3
    // Yanlis reset code
    // ============================================================

    private static void testConfirmPasswordResetWithWrongCode() {

        Fixture f = fixture();

        requestReset(
                f,
                "user@test.com"
        );

        Map<String, Object> request =
                confirmRequest(
                        "confirm-wrong-1",
                        "user@test.com",
                        "WRONG-CODE",
                        "NewPassword123"
                );

        String response =
                f.router.handle(
                        Json.stringify(request),
                        f.session
                );

        check(
                response.contains("\"status\":\"ERROR\"")
                        || response.contains("\"status\": \"ERROR\""),
                "wrong reset code should return ERROR"
        );

        check(
                response.contains(
                        "invalid or expired"
                ),
                "wrong reset code should return generic invalid/expired message"
        );
    }

    // ============================================================
    // TEST 4
    // 3 yanlis deneme -> admin approval
    // ============================================================

    private static void testThreeWrongAttemptsRequireAdminApproval() {

        Fixture f = fixture();

        requestReset(
                f,
                "user@test.com"
        );

        String response1 =
                f.router.handle(
                        Json.stringify(
                                confirmRequest(
                                        "wrong-1",
                                        "user@test.com",
                                        "WRONG-1",
                                        "NewPassword123"
                                )
                        ),
                        f.session
                );

        String response2 =
                f.router.handle(
                        Json.stringify(
                                confirmRequest(
                                        "wrong-2",
                                "user@test.com",
                                "WRONG-2",
                                "NewPassword123"
                            )
                        ),
                        f.session
                );

        String response3 =
                f.router.handle(
                        Json.stringify(
                                confirmRequest(
                                        "wrong-3",
                                        "user@test.com",
                                        "WRONG-3",
                                        "NewPassword123"
                                )
                        ),
                        f.session
                );
                
        check(
                response1.contains("\"status\":\"ERROR\"")
                        || response1.contains("\"status\": \"ERROR\""),
                "first wrong reset attempt should return ERROR"
        );

        check(
                response2.contains("\"status\":\"ERROR\"")
                        || response2.contains("\"status\": \"ERROR\""),
                "second wrong reset attempt should return ERROR"
        );

        check(
                response3.contains(
                        "requires Super Admin approval"
                ),
                "third wrong reset attempt should require Super Admin approval"
        );
    }

    // ============================================================
    // TEST 5
    // Normal user approval yapamamali
    // ============================================================

    private static void testNormalUserCannotApproveReset() {

        Fixture f = fixture();

        lockUserReset(f);

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                "approve-normal-user"
        );

        request.put(
                "action",
                "APPROVE_PASSWORD_RESET"
        );

        request.put(
                "username",
                "user@test.com"
        );

        request.put(
                "password",
                "OldPassword123"
        );

        request.put(
                "filter",
                Map.of(
                        "id",
                        f.user.id()
                )
        );

        String response =
                f.router.handle(
                        Json.stringify(request),
                        f.session
                );

        check(
                response.contains("\"status\":\"ERROR\"")
                        || response.contains("\"status\": \"ERROR\"")
                        || response.contains("\"status\":\"UNAUTHORIZED\"")
                        || response.contains("\"status\": \"UNAUTHORIZED\""),
                "normal user must not approve password reset"
        );
    }

    // ============================================================
    // TEST 6
    // Super Admin approval
    // ============================================================

    private static void testSuperAdminCanApproveReset() {

        Fixture f = fixture();

        lockUserReset(f);

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                "approve-super-admin"
        );

        request.put(
                "action",
                "APPROVE_PASSWORD_RESET"
        );

        request.put(
                "username",
                "admin@test.com"
        );

        request.put(
                "password",
                "AdminPassword123"
        );

        request.put(
                "filter",
                Map.of(
                        "id",
                        f.user.id()
                )
        );

        String response =
                f.router.handle(
                        Json.stringify(request),
                        f.session
                );

        check(
                response.contains("\"status\":\"OK\"")
                        || response.contains("\"status\": \"OK\""),
                "Super Admin should approve locked password reset"
        );

        /*
         * Su an development asamasinda resetCode response'ta donuyor.
         * Email delivery geldikten sonra bu assertion kaldirilacak.
         */
        check(
                response.contains("resetCode"),
                "admin approval should return new reset code in development mode"
        );
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static void requestReset(
            Fixture f,
            String email
    ) {

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                "request-helper"
        );

        request.put(
                "action",
                "REQUEST_PASSWORD_RESET"
        );

        request.put(
                "document",
                Map.of(
                        "email",
                        email
                )
        );

        f.router.handle(
                Json.stringify(request),
                f.session
        );
    }

    private static Map<String, Object> confirmRequest(
            String requestId,
            String email,
            String resetCode,
            String newPassword
    ) {

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "requestId",
                requestId
        );

        request.put(
                "action",
                "CONFIRM_PASSWORD_RESET"
        );

        request.put(
                "document",
                Map.of(
                        "email",
                        email,
                        "resetCode",
                        resetCode,
                        "newPassword",
                        newPassword
                )
        );

        return request;
    }

    private static void lockUserReset(
            Fixture f
    ) {

        requestReset(
                f,
                "user@test.com"
        );

        for (int i = 1; i <= 3; i++) {

            f.router.handle(
                    Json.stringify(
                            confirmRequest(
                                    "lock-" + i,
                                    "user@test.com",
                                    "WRONG-" + i,
                                    "NewPassword123"
                            )
                    ),
                    f.session
            );
        }
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

        EventBus eventBus =
                new EventBus();

        RequestFileService files =
                new RequestFileService(
                        "test-files"
                );

        StringWriter sessionOutput =
                new StringWriter();

        PrintWriter sessionWriter =
                new PrintWriter(
                        sessionOutput,
                        true
                );

        ClientSession session =
                new ClientSession(
                        sessionWriter,
                        "password-reset-test-client"
                );


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

        Router router =
                new Router(
                        store,
                        eventBus,
                        auth,
                        files,
                        audit,
                        true
                );

        return new Fixture(
                store,
                auth,
                audit,
                router,
                session,
                admin,
                user
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
        AuditService audit,
        Router router,
        ClientSession session,
        User admin,
        User user
    ) {
    }
}
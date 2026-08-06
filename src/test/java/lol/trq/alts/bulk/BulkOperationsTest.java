package lol.trq.alts.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lol.trq.alts.auth.AccountStatus;
import lol.trq.alts.auth.AltAccountService;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.Test;

/**
 * The pacing and stopping rules, exercised against a stub account service so no network is involved.
 * What each test defends is a way a real run goes wrong: too many at once earns a rate limit, a retry
 * that never gives up turns one dead credential into an endless loop, and a run that ignores a stated
 * rate limit earns a longer ban than the one it is already serving.
 */
class BulkOperationsTest {

    private static final BulkOptions FAST = new BulkOptions(4, Duration.ZERO, 2, Duration.ofMillis(1), true);

    private static AltAccount account(int index) {
        return AltAccount.of("0000000-0000-4000-8000-" + index, "Alt" + index, "token", AccountType.MICROSOFT);
    }

    private static List<AltAccount> accounts(int count) {
        List<AltAccount> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(account(i));
        }
        return list;
    }

    /** Builds a bulk surface whose every account operation is the given function. */
    private static BulkOperations bulkOver(Function<AltAccount, CompletableFuture<AccountStatus>> operation) {
        AltAccountService service = new AltAccountService() {
            @Override
            public CompletableFuture<AccountStatus> check(AltAccount account) {
                return operation.apply(account);
            }

            @Override
            public CompletableFuture<AccountStatus> refresh(AltAccount account) {
                return operation.apply(account);
            }
        };
        // The import service is unused by these runs; a null-injecting login service would need a config.
        return new BulkOperationsImpl(new UnusedLoginService(), service);
    }

    @Test
    void everyAccountRunsAndTheReportCountsThem() throws Exception {
        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.valid(account)));

        BulkReport report =
                bulk.refreshAll(accounts(10), FAST, BulkProgress.NONE).report().get();

        assertEquals(10, report.succeeded());
        assertTrue(report.complete());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void noMoreThanTheConfiguredNumberRunAtOnce() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        BulkOperations bulk = bulkOver(account -> CompletableFuture.supplyAsync(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return AccountStatus.valid(account);
        }));

        bulk.refreshAll(accounts(12), FAST.withConcurrency(3), BulkProgress.NONE)
                .report()
                .get();

        assertTrue(peak.get() <= 3, "the concurrency bound must hold, saw " + peak.get());
        assertTrue(peak.get() > 1, "the run must actually be concurrent, saw " + peak.get());
    }

    @Test
    void spacingHoldsBetweenStarts() throws Exception {
        BulkOptions spaced = new BulkOptions(1, Duration.ofMillis(30), 0, Duration.ZERO, true);
        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.valid(account)));

        long start = System.currentTimeMillis();
        bulk.refreshAll(accounts(4), spaced, BulkProgress.NONE).report().get();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 90, "four entries spaced 30ms apart cannot finish in " + elapsed + "ms");
    }

    @Test
    void aTransientFailureIsRetriedUpToTheBudgetAndNoFurther() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BulkOperations bulk = bulkOver(account -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(AccountStatus.failure(
                    account, AccountStatus.State.UNREACHABLE, FailureReason.NETWORK, "service is down"));
        });

        BulkReport report = bulk.refreshAll(List.of(account(0)), FAST, BulkProgress.NONE)
                .report()
                .get();

        assertEquals(3, calls.get(), "one attempt plus two retries");
        assertEquals(3, report.results().get(0).attempts());
        assertFalse(report.results().get(0).success());
    }

    /** Retrying a refused credential spends rotations to no purpose; only a transient failure is retried. */
    @Test
    void aRefusedCredentialIsNeverRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BulkOperations bulk = bulkOver(account -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(AccountStatus.failure(
                    account, AccountStatus.State.REAUTH_REQUIRED, FailureReason.REAUTH_REQUIRED, "spent"));
        });

        bulk.refreshAll(List.of(account(0)), FAST, BulkProgress.NONE).report().get();

        assertEquals(1, calls.get(), "a permanently spent credential is not worth a second attempt");
    }

    @Test
    void aStatedRateLimitStopsTheRunAndSaysSo() throws Exception {
        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.failure(
                account, AccountStatus.State.UNREACHABLE, FailureReason.NETWORK, "slow down", Duration.ofSeconds(30))));

        BulkReport report = bulk.refreshAll(accounts(20), FAST.withConcurrency(1), BulkProgress.NONE)
                .report()
                .get();

        assertTrue(report.stoppedEarly(), "a service asking to be left alone must end the run");
        assertFalse(report.cancelled(), "stopping early is not the same as being cancelled");
        assertTrue(report.results().size() < 20, "the run must not have worked through the whole list");
    }

    @Test
    void aRateLimitCanBeWaitedOutInsteadWhenTheCallerInsists() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BulkOperations bulk = bulkOver(account -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(AccountStatus.failure(
                        account,
                        AccountStatus.State.UNREACHABLE,
                        FailureReason.NETWORK,
                        "slow down",
                        Duration.ofMillis(5)));
            }
            return CompletableFuture.completedFuture(AccountStatus.valid(account));
        });

        BulkReport report = bulk.refreshAll(List.of(account(0)), FAST.withoutStoppingOnRateLimit(), BulkProgress.NONE)
                .report()
                .get();

        assertFalse(report.stoppedEarly());
        assertEquals(1, report.succeeded(), "the entry succeeded on the attempt after the wait");
    }

    @Test
    void cancellingStopsStartingNewEntries() throws Exception {
        BulkOptions slow = new BulkOptions(1, Duration.ofMillis(20), 0, Duration.ZERO, true);
        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.valid(account)));

        BulkHandle handle = bulk.refreshAll(accounts(50), slow, BulkProgress.NONE);
        Thread.sleep(60);
        handle.cancel();
        BulkReport report = handle.report().get();

        assertTrue(report.cancelled());
        assertTrue(
                report.results().size() < 50,
                "cancelling must stop the run, saw " + report.results().size());
        assertFalse(report.complete());
    }

    @Test
    void anEmptyListCompletesRatherThanHanging() throws Exception {
        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.valid(account)));

        BulkReport report =
                bulk.checkAll(List.of(), FAST, BulkProgress.NONE).report().get();

        assertEquals(0, report.results().size());
        assertTrue(report.complete());
    }

    @Test
    void progressReportsEveryEntryOnceAndFinishesOnce() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();

        BulkOperations bulk = bulkOver(account -> CompletableFuture.completedFuture(AccountStatus.valid(account)));
        bulk.refreshAll(accounts(6), FAST, new BulkProgress() {
                    @Override
                    public void started(int index, int total, String label) {
                        started.incrementAndGet();
                    }

                    @Override
                    public void completed(int index, int total, BulkEntryResult result) {
                        completed.incrementAndGet();
                    }

                    @Override
                    public void finished(BulkReport report) {
                        finished.incrementAndGet();
                    }
                })
                .report()
                .get();

        assertEquals(6, started.get());
        assertEquals(6, completed.get());
        assertEquals(1, finished.get(), "the run ends once, however it ends");
    }

    @Test
    void anExceptionFromTheOperationFailsOnlyThatEntry() throws Exception {
        BulkOperations bulk = bulkOver(account -> account.username().equals("Alt1")
                ? CompletableFuture.failedFuture(new IllegalStateException("something broke"))
                : CompletableFuture.completedFuture(AccountStatus.valid(account)));

        BulkReport report =
                bulk.refreshAll(accounts(3), FAST, BulkProgress.NONE).report().get();

        assertEquals(2, report.succeeded());
        assertEquals(1, report.failures().size());
        assertEquals(FailureReason.UNKNOWN, report.failures().get(0).reason());
    }

    /** Stands in for a login service the account-only runs never reach. */
    private static final class UnusedLoginService implements lol.trq.alts.auth.AltLoginService {

        private static CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> unused() {
            throw new AssertionError("the import routes are not exercised by an account run");
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginSession(
                String sessionToken, lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginOffline(
                String name, lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginMicrosoft(
                lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginCookie(
                String cookieData, lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginCookieFile(
                java.nio.file.Path file, lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginRefreshToken(
                String refreshToken, lol.trq.alts.model.LoginMode mode) {
            return unused();
        }

        @Override
        public CompletableFuture<lol.trq.alts.auth.AltLoginCallback.LoginResult> loginAccount(AltAccount account) {
            return unused();
        }
    }
}

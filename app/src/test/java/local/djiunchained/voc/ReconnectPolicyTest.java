package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReconnectPolicyTest {
    @Test
    public void backoffProgressesAndCapsAtTenSeconds() {
        ReconnectPolicy policy = new ReconnectPolicy();

        assertEquals(new ReconnectPolicy.Attempt(1, 1_000), policy.nextAttempt());
        assertEquals(new ReconnectPolicy.Attempt(2, 2_000), policy.nextAttempt());
        assertEquals(new ReconnectPolicy.Attempt(3, 5_000), policy.nextAttempt());
        assertEquals(new ReconnectPolicy.Attempt(4, 10_000), policy.nextAttempt());
        assertEquals(new ReconnectPolicy.Attempt(5, 10_000), policy.nextAttempt());
    }

    @Test
    public void resetStartsBackoffFromFirstAttempt() {
        ReconnectPolicy policy = new ReconnectPolicy();
        policy.nextAttempt();
        policy.nextAttempt();

        policy.reset();

        assertEquals(0, policy.failureCount());
        assertEquals(new ReconnectPolicy.Attempt(1, 1_000), policy.nextAttempt());
    }
}

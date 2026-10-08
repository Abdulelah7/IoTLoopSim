package org.iotloopsim.validation;

import static org.junit.Assert.*;

import java.util.List;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.AppRuntime;
import org.iotloopsim.model.Link;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.junit.Test;

/** Fast checks of the deterministic (verification) rows of {@link LifecycleValidation} and of the equivalence rule. */
public class LifecycleValidationTest {

    @Test public void otaAllAtOnceMatchesClosedForm() {
        double sim = LifecycleValidation.otaCampaignS(3, 16, 30, 0.01, PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "all-at-once", null));
        assertEquals(LifecycleValidation.otaExpectedBatchS(3, 16, 30, 0.01), sim, 1e-6);
    }

    @Test public void coldMigrationDowntimeMatchesClosedForm() {
        long mib = 1L << 20;
        double sim = LifecycleValidation.migrationDowntimeMs(AppRuntime.MigrationMode.COLD, 0.5, mib, 100e6, SimTime.ms(5), 1.0,
                LifecycleValidation.Dest.REGISTRY, 50, Link.Fidelity.ANALYTICAL);
        assertEquals((0.5 + 8.0 * mib / 100e6 + 0.005 + 1.0) * 1000, sim, 1e-3);
    }

    @Test public void equivalenceNeedsTheWholeCiInsideTheMargin() {
        // rel error 1 %, CI half-width 1.5 % of expected: passes (expected inside CI) but is not equivalent at a 2 % margin
        Check c = new Check("g", "t", "-", 100, List.of(99.5, 101.0, 102.5, 101.0, 101.0), 0.02, "-");
        assertTrue(c.pass());
        assertFalse(LifecycleValidation.equivalent(c));
        assertTrue(LifecycleValidation.equivalent(new Check("g", "t", "-", 100, List.of(100.1), 0.002, "-")));
    }
}

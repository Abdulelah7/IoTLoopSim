package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.Entity;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.policy.RolloutPolicy;

/**
 * Over-the-air firmware campaigns. The image travels from the registry to the device's gateway over the network and
 * then over the gateway's downlink: each gateway serves the downloads of one technology one after another, and each
 * download occupies the downlink for its airtime divided by the gateway's downlink duty cycle (default 10 % for LoRa,
 * the EU868 RX2 sub-band, and 100 % for other technologies). The device is charged receive energy for its own
 * airtime. It then installs the image and reboots; installation fails with a given probability. Failed devices are
 * rolled back after a delay (if enabled) or stay failed. Rollout policies batch devices and can abort a campaign.
 */
public final class UpdateManager {
    private final IoTWorld world;
    private final List<Runnable> starters = new ArrayList<>();

    UpdateManager(IoTWorld world) { this.world = world; }

    /**
     * Registers an OTA campaign starting {@code start} ns after world start: matching devices not already on
     * {@code version} are batched by {@code rollout}, download {@code sizeKB} KiB (radio airtime, duty cycle and
     * rx energy charged), install for a time sampled from {@code installS} (s) and fail with probability {@code
     * failProb}; failed devices are rolled back and repaired after {@code rollbackNs} ns if {@code rollback} is
     * set. Offline devices are skipped.
     */
    public void campaign(String name, Predicate<Entity> targets, String version, double sizeKB, long start, RolloutPolicy rollout,
                         Distribution installS, double failProb, boolean rollback, long rollbackNs) {
        campaign(name, targets, version, sizeKB, start, rollout, installS, failProb, rollback, rollbackNs, -1);
    }

    /**
     * As above, with an explicit gateway downlink duty cycle for the firmware transfer ({@code gatewayDutyCycle} in
     * (0, 1]; a value of 0 or less selects the default: 0.1 for LoRa technologies and 1.0 otherwise).
     */
    public void campaign(String name, Predicate<Entity> targets, String version, double sizeKB, long start, RolloutPolicy rollout,
                         Distribution installS, double failProb, boolean rollback, long rollbackNs, double gatewayDutyCycle) {
        if (gatewayDutyCycle > 1) throw new IllegalArgumentException("gatewayDutyCycle must be in (0, 1]: " + gatewayDutyCycle);
        starters.add(() -> world.sim().schedule(start, "ota-start", () -> {
            List<IoTDevice> devs = new ArrayList<>();
            for (IoTDevice d : world.devices()) if (targets.test(d) && !version.equals(d.firmware())) devs.add(d);
            world.lifecycle().record("campaign", name, "planned", "started", devs.size() + " devices");
            new Run(name, version, sizeKB, rollout, installS, failProb, rollback, rollbackNs, gatewayDutyCycle, rollout.batches(devs)).next(0);
        }));
    }

    private final class Run {
        final String name, version;
        final double sizeKB;
        final RolloutPolicy rollout;
        final Distribution installS;
        final double failProb;
        final boolean rollback;
        final long rollbackNs;
        final double gwDuty;
        final List<List<IoTDevice>> batches;
        final long t0 = world.sim().now();
        int ok, failed, done;
        final SplittableRandom r;

        Run(String name, String version, double sizeKB, RolloutPolicy rollout, Distribution installS, double failProb,
            boolean rollback, long rollbackNs, double gwDuty, List<List<IoTDevice>> batches) {
            this.name = name; this.version = version; this.sizeKB = sizeKB; this.rollout = rollout; this.installS = installS;
            this.failProb = failProb; this.rollback = rollback; this.rollbackNs = rollbackNs; this.gwDuty = gwDuty; this.batches = batches;
            this.r = world.sim().stream("ota:" + name);
        }

        void next(int b) {
            if (b >= batches.size()) { finish("completed"); return; }
            List<IoTDevice> batch = batches.get(b);
            int[] left = {batch.size()};
            if (batch.isEmpty()) { next(b + 1); return; }
            for (IoTDevice d : batch) update(d, () -> {
                if (--left[0] == 0) {
                    double rate = ok + failed == 0 ? 0 : failed / (double) (ok + failed);   // skipped/interrupted devices do not dilute it
                    if (!rollout.proceed(b + 1, rate)) { finish("aborted"); return; }
                    if (b + 1 >= batches.size()) { next(b + 1); return; }   // no pause after the last batch
                    world.sim().schedule(rollout.batchIntervalNs(), "ota-batch", () -> next(b + 1));
                }
            });
        }

        void update(IoTDevice d, Runnable after) {
            if (!d.isConnected()) { world.sim().metrics().inc("update.skipped_offline"); done++; after.run(); return; }
            String oldVersion = d.firmware();
            d.beginUpdate();
            long t = world.sim().now();
            int bytes = (int) Math.round(sizeKB * 1024);
            AccessTechnology tech = d.tech();
            long air = tech.airtimeNs(bytes + bytes / 50); // ~2% protocol overhead for block-wise transfer
            double duty = gwDuty > 0 ? gwDuty : (tech.loraSf() > 0 ? 0.1 : 1.0);
            Message fw = new Message(Message.Kind.FIRMWARE, bytes, t, null);
            ComputeNode repo = world.registryFor(d.gateway() == null ? null : d.gateway());
            Runnable radio = () -> {
              long now = world.sim().now();
              Gateway gw = d.gateway();
              long occupancy = Math.round(air / duty);
              long end = gw != null ? gw.reserveDownlink(tech, now, occupancy) : now + occupancy;
              long dl = end - now + tech.latencyNs() * tech.frames(bytes);
              world.sim().metrics().record("update.download_s", SimTime.toSeconds(dl));
              world.sim().schedule(dl, "ota-download", () -> {
                if (interrupted(d, after)) return;
                d.consume(tech.rxPowerW() * air / 1e9, "ota");
                world.sim().schedule(SimTime.s(installS.sample(r)), "ota-install", () -> {
                    if (interrupted(d, after)) return;
                    boolean success = r.nextDouble() >= failProb;
                    done++;
                    world.sim().metrics().record("update.device_duration_s", SimTime.toSeconds(world.sim().now() - t));
                    if (success) {
                        ok++;
                        d.setFirmware(version);
                        d.endUpdate(true);
                        world.sim().metrics().inc("update.succeeded");
                    } else {
                        failed++;
                        world.sim().metrics().inc("update.failed");
                        d.endUpdate(false);
                        if (rollback) world.sim().schedule(rollbackNs, "ota-rollback", () -> {
                            d.setFirmware(oldVersion);
                            d.repair();
                            world.sim().metrics().inc("update.rolled_back");
                        });
                    }
                    after.run();
                });
              });
            };
            if (repo != null && d.gateway() != null) {
                world.network().send(fw, repo, d.gateway(), Protocol.preset("tcp"), (okNet, reason) -> {
                    if (okNet) radio.run();
                    else {   // the image never reached the gateway
                        done++;
                        world.sim().metrics().inc("update.download_failed");
                        d.endUpdate(true);   // back to service on the old firmware
                        after.run();
                    }
                });
            } else radio.run();
        }

        /** The device was depleted, failed or retired during the update: count it and continue the campaign. */
        private boolean interrupted(IoTDevice d, Runnable after) {
            if (d.state() == DeviceState.UPDATING && d.isUp()) return false;
            done++;
            world.sim().metrics().inc("update.interrupted");
            d.endUpdate(true);   // only clears the in-progress flag; the state is left as it is
            after.run();
            return true;
        }

        void finish(String how) {
            world.sim().metrics().record("update.campaign_duration_s", SimTime.toSeconds(world.sim().now() - t0));
            world.sim().metrics().inc("update.campaigns_" + how);
            world.lifecycle().record("campaign", name, "started", how, ok + " ok / " + failed + " failed");
        }
    }

    void start() { for (Runnable r : starters) r.run(); }
}

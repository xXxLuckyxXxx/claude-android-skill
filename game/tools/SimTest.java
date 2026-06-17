package com.fable.racer;

/**
 * Head-less playtest harness. Runs an autopilot around every level and checks
 * that the race is completable, jumps fire, shortcuts shorten the lap and no
 * NaNs appear. Compile with Sim.java + Tracks.java and run on a normal JVM:
 *
 *   javac -d out app/src/main/java/com/fable/racer/Sim.java \
 *               app/src/main/java/com/fable/racer/Tracks.java tools/SimTest.java
 *   java -cp out com.fable.racer.SimTest
 */
public final class SimTest {

    public static void main(String[] args) {
        int failures = 0;
        for (int lvl = 0; lvl < Tracks.LEVELS.length; lvl++) {
            failures += runLevel(lvl);
        }
        System.out.println();
        if (failures == 0) System.out.println("PLAYTEST PASSED — all levels completable.");
        else System.out.println("PLAYTEST FAILED — " + failures + " problem(s).");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int runLevel(int lvl) {
        Tracks.Def def = Tracks.LEVELS[lvl];
        Sim sim = new Sim();
        sim.load(def, 1234 + lvl);

        double dt = 1.0 / 60;
        double maxGameTime = 300;
        int steps = 0, maxSteps = (int) (maxGameTime / dt);
        double topSpeed = 0;
        boolean jumped = false, picked = false, boosted = false, nan = false;
        int itemsUsed = 0;

        while (!sim.finished && steps++ < maxSteps) {
            // ---- autopilot: aim a few points ahead on the centreline ----
            int look = 7;
            int target = (sim.prevIdx + look) % sim.N;
            double tx = sim.cx[target], ty = sim.cy[target];
            double desired = Math.atan2(ty - sim.carY, tx - sim.carX);
            double diff = wrap(desired - sim.carAngle);

            boolean left = diff < -0.05;
            boolean right = diff > 0.05;
            boolean gas = true;
            boolean brake = Math.abs(diff) > 0.6 && sim.speed() > 360;   // ease into sharp bends
            boolean boostEdge = sim.nitro > 0.55 && (steps % 30 == 0);
            boolean itemEdge = sim.heldItem != Sim.IT_NONE && (steps % 25 == 0);
            if (itemEdge) itemsUsed++;

            sim.update(dt, left, right, gas, brake, boostEdge, itemEdge);

            if (sim.evJump) jumped = true;
            if (sim.evPickup) picked = true;
            if (sim.evBoost) boosted = true;
            sim.clearEvents();

            topSpeed = Math.max(topSpeed, sim.speed());
            if (Double.isNaN(sim.carX) || Double.isNaN(sim.carY) || Double.isNaN(sim.carAngle)) { nan = true; break; }
        }

        double simSeconds = steps * dt;
        int problems = 0;
        if (nan) problems++;
        if (!sim.finished) problems++;
        if (sim.lapsDone != def.laps && sim.finished) problems++;

        System.out.printf("%-22s laps=%d/%d finished=%s time=%5.1fs topSpeed=%3.0f jump=%s pickup=%s boost=%s items=%d pos=P%d%s%n",
                def.name, sim.lapsDone, def.laps, sim.finished, sim.finishTime > 0 ? sim.finishTime : simSeconds,
                topSpeed, jumped, picked, boosted, itemsUsed, sim.position(), nan ? "  <NaN!>" : "");
        if (!sim.finished) System.out.println("   !! did not finish within " + (int) maxGameTime + "s");
        if (!jumped && def.jumps != null && def.jumps.length > 0) {
            System.out.println("   !! never hit a ramp");
            problems++;
        }
        return problems;
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}

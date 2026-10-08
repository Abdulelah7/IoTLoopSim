package org.iotloopsim.bridge;

/** Entry point of the all-in-one JAR: "gym" starts the Gym bridge, everything else goes to the experiment CLI. */
public final class Launcher {
    /**
     * Dispatches {@code gym} to {@link GymServer#main(String[])} and all other arguments to {@link
     * org.iotloopsim.experiment.Main#main(String[])}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("gym")) GymServer.main(args);
        else org.iotloopsim.experiment.Main.main(args);
    }
}

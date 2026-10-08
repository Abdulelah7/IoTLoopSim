package org.iotloopsim.model.policy;

/** Implement and declare via ServiceLoader to contribute policies (call {@link PolicyRegistry#register} in {@link #register()}). */
public interface PolicyProvider {
    /** Registers factories, e.g. {@code PolicyRegistry.selector("name", cfg -> new MySelector(cfg.num("k", 1)))}. */
    void register();
}

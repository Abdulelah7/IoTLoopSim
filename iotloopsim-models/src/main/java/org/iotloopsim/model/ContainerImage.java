package org.iotloopsim.model;

/** A container image that must be present on a node before an instance can start. */
public record ContainerImage(String name, double sizeMB) {
    /** Returns the image size in bytes ({@code sizeMB} times 1024 times 1024, rounded). */
    public long bytes() { return Math.round(sizeMB * 1024 * 1024); }
}

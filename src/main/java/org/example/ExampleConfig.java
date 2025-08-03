package org.example;

/**
 * Example configuration POJO.
 *
 * Configurations are saved and loaded to JSON files
 *
 * All fields should be public and mutable.
 *
 * Fields to static inner classes generate nested JSON objects.
 */
public class ExampleConfig {
    public boolean esp = false;

    public final ExampleModuleConfig exampleModule = new ExampleModuleConfig();
    public static class ExampleModuleConfig {
        public boolean enabled = true;
        public int delayTicks = 250;
    }

    public final ExampleWanderConfig wander = new ExampleWanderConfig();
    public static final class ExampleWanderConfig {
        public boolean enabled = false;
        public int radius = 2000;
        public int minRadius = 100;
    }
}

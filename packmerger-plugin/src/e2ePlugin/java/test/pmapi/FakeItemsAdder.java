package test.pmapi;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * E2E-only stand-in named "ItemsAdder" (never shipped): makes ItemsAdder look installed
 * so PackMerger's competing-host check reads plugins/ItemsAdder/config.yml.
 */
public class FakeItemsAdder extends JavaPlugin {
}

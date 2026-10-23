package fakes.tamework.api;

public record HusbandryToolContext(String itemId, int quantity, double durability, double maxDurability) {
    public boolean present() {
        return itemId != null;
    }
}

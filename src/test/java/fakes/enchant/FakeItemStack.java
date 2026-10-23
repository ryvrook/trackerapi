package fakes.enchant;

/**
 * Stands in for the engine's {@code ItemStack}, whose constructor reads the item asset store and so cannot be built
 * without a server. It has the two methods the binding reads, {@code getItemId()} and {@code isEmpty()}.
 */
public final class FakeItemStack {
    private final String itemId;

    public FakeItemStack(String itemId) {
        this.itemId = itemId;
    }

    public String getItemId() {
        return itemId;
    }

    public boolean isEmpty() {
        return "Empty".equals(itemId);
    }
}

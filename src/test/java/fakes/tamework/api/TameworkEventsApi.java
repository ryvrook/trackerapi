package fakes.tamework.api;

import java.util.function.Consumer;

/** The bus of the mod, one subscribe per event class. */
public interface TameworkEventsApi {
    <E extends TameworkEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> consumer);
}

package fakes.melodies.data;

/** The fake song record, the name and the length in milliseconds as the real {@code Melody} gives them. */
public record Melody(String name, int duration) {
}

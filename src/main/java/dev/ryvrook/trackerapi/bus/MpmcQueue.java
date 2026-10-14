package dev.ryvrook.trackerapi.bus;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A bounded lock free queue with many producers and many consumers, the Vyukov design. Every world
 * thread offers into it and the dispatch thread polls it. An offer is one CAS on the tail when it wins
 * the slot and nothing else, a poll one CAS on the head. A full queue refuses the offer at once rather
 * than blocking a world thread, and the caller counts the drop.
 *
 * <p>Capacity is rounded up to a power of two. Slots are padded by a sequence array rather than by field
 * padding, which keeps the class plain Java.
 */
public final class MpmcQueue<E> {
    private final int mask;
    private final AtomicReferenceArray<E> slots;
    private final AtomicLongArray sequences;
    private final AtomicLong tail = new AtomicLong();
    private final AtomicLong head = new AtomicLong();

    public MpmcQueue(int capacity) {
        int size = 2;
        while (size < Math.max(2, capacity)) {
            size <<= 1;
        }
        this.mask = size - 1;
        this.slots = new AtomicReferenceArray<>(size);
        this.sequences = new AtomicLongArray(size);
        for (int i = 0; i < size; i++) {
            sequences.set(i, i);
        }
    }

    public int capacity() {
        return mask + 1;
    }

    /** Adds the element at the tail. False when the queue is full. */
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        long pos = tail.get();
        while (true) {
            int index = (int) (pos & mask);
            long seq = sequences.get(index);
            long diff = seq - pos;
            if (diff == 0) {
                if (tail.compareAndSet(pos, pos + 1)) {
                    slots.set(index, e);
                    sequences.set(index, pos + 1);
                    return true;
                }
                pos = tail.get();
            } else if (diff < 0) {
                return false; // the slot still holds an element the consumer has not taken, so the queue is full
            } else {
                pos = tail.get();
            }
        }
    }

    /** Takes the element at the head, or null when the queue is empty. */
    public E poll() {
        long pos = head.get();
        while (true) {
            int index = (int) (pos & mask);
            long seq = sequences.get(index);
            long diff = seq - (pos + 1);
            if (diff == 0) {
                if (head.compareAndSet(pos, pos + 1)) {
                    E e = slots.get(index);
                    slots.set(index, null);
                    sequences.set(index, pos + mask + 1);
                    return e;
                }
                pos = head.get();
            } else if (diff < 0) {
                return null;
            } else {
                pos = head.get();
            }
        }
    }

    /** Roughly how many elements are waiting. */
    public int size() {
        long t = tail.get();
        long h = head.get();
        return (int) Math.max(0, Math.min(mask + 1, t - h));
    }

    public boolean isEmpty() {
        return head.get() >= tail.get();
    }
}

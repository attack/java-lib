package com.wavefront.common;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.RandomAccess;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A basic ring buffer with an ability to evict values on overflow.
 *
 * @param <T> type of objects stored
 *
 * @author vasily@wavefont.com
 */
@NotThreadSafe
public class EvictingRingBuffer<T> extends AbstractCollection<T>
    implements Queue<T>, RandomAccess, Cloneable, Serializable {
  private static final long serialVersionUID = -4686283540164095706L;

  private final List<T> buffer;
  private final int bufferSize;
  private int headPtr;
  private int tailPtr;
  private final boolean throwOnOverflow;

  /**
   * @param capacity desired capacity.
   */
  public EvictingRingBuffer(int capacity) {
    this(capacity, false, null, false);
  }

  /**
   * @param capacity          desired capacity
   * @param throwOnOverflow   Disables auto-eviction on overflow. When full capacity is
   *                          reached, all subsequent append() operations would throw
   *                          {@link IllegalStateException} if this parameter is true,
   *                          or evict the oldest value if this parameter is false.
   */
  public EvictingRingBuffer(int capacity, boolean throwOnOverflow) {
    this(capacity, throwOnOverflow, null, false);
  }

  /**
   * @param capacity      desired capacity.
   * @param defaultValue  pre-fill the buffer with this default value.
   */
  public EvictingRingBuffer(int capacity, @Nullable T defaultValue) {
    this(capacity, false, defaultValue, true);
  }

  /**
   * @param capacity         desired capacity.
   * @param throwOnOverflow  disables auto-eviction on overflow. When full capacity is
   *                         reached, all subsequent append() operations would throw
   *                         {@link IllegalStateException} if this parameter is true,
   *                         or evict the oldest value if this parameter is false.
   * @param defaultValue     pre-fill the buffer with this default value.
   */
  public EvictingRingBuffer(int capacity, boolean throwOnOverflow, @Nullable T defaultValue) {
    this(capacity, throwOnOverflow, defaultValue, true);
  }

  protected EvictingRingBuffer(int capacity, boolean throwOnOverflow, @Nullable T defaultValue,
                             boolean preFill) {
    this.buffer = new ArrayList<>(Collections.nCopies(capacity + 1, defaultValue));
    this.buffer.set(0, null);
    this.bufferSize = capacity + 1;
    this.throwOnOverflow = throwOnOverflow;
    this.headPtr = 0;
    this.tailPtr = preFill ? capacity : 0;
  }

  /**
   * Returns buffer capacity (i.e. max number of elements this buffer can hold).
   *
   * @return buffer capacity
   */
  public int capacity() {
    return bufferSize - 1;
  }

  @Nonnull
  @Override
  public Iterator<T> iterator() {
    return toList().iterator();
  }

  /**
   * Returns number of elements in the buffer.
   *
   * @return number of elements
   */
  public int size() {
    return tailPtr - headPtr + (tailPtr < headPtr ? bufferSize : 0);
  }

  /**
   * Return the element at the specified position in the buffer.
   *
   * @param index index of the element to return
   * @return the element at the specified position in the buffer
   * @throws IndexOutOfBoundsException if the index is out of range
   *         ({@code index < 0 || index >= size()})
   */
  public T get(int index) {
    if (index < 0 || index >= size()) {
      throw new IndexOutOfBoundsException("Index out of bounds: " + index +
          ", expected: [0; " + size() + ")");
    }
    return buffer.get(wrap(headPtr + index + 1));
  }

  /**
   * Add a value at the end of the ring buffer.
   *
   * @param value element to be appended to the end of the buffer
   * @return true (as specified by {@link Collection#add(Object)})
   * @throws IllegalStateException if the element cannot be added at this
   *         time due to capacity restrictions
   */
  @Override
  public boolean add(T value) {
    if (offer(value)) {
      return true;
    } else {
      throw new IllegalStateException("Buffer capacity exceeded: " + (bufferSize - 1));
    }
  }

  /**
   * Inserts the specified element into this queue if it is possible to do so
   * immediately without violating capacity restrictions.
   *
   * @param value element to insert
   * @return true if the element was added, else false
   */
  @Override
  public boolean offer(T value) {
    if (size() == bufferSize - 1) {
      if (throwOnOverflow) {
        return false;
      } else {
        // evict oldest value
        headPtr = wrap(headPtr + 1);
        buffer.set(headPtr, null); // to allow evicted value to be GC'd
      }
    }
    tailPtr = wrap(tailPtr + 1);
    buffer.set(tailPtr, value);
    return true;
  }

  /**
   * Returns a {@code List<T>} containing all the elements in the buffer
   * in proper sequence (first to last element).
   *
   * @return a {@code List<T>} containing all the elements in the buffer
   *         in proper sequence
   */
  public List<T> toList() {
    if (tailPtr == headPtr) {
      return Collections.emptyList();
    } else if (tailPtr > headPtr) {
      return Collections.unmodifiableList(buffer.subList(headPtr + 1, tailPtr + 1));
    } else {
      return Collections.unmodifiableList(
          Stream.concat(
              buffer.subList(headPtr + 1, bufferSize).stream(),
              buffer.subList(0, tailPtr + 1).stream()).
              collect(Collectors.toList()));
    }
  }

  @Override
  @Nonnull
  public Object[] toArray() {
    if (tailPtr == headPtr) {
      return new Object[]{};
    } else if (tailPtr > headPtr) {
      return buffer.subList(headPtr + 1, tailPtr + 1).toArray();
    } else {
      return Stream.concat(
          buffer.subList(headPtr + 1, bufferSize).stream(),
          buffer.subList(0, tailPtr + 1).stream()).toArray();
    }
  }

  /**
   * Retrieves and removes the head of this buffer.
   *
   * @return removed element
   * @throws NoSuchElementException if buffer is empty
   */
  @Override
  public T remove() {
    T t = poll();
    if (t == null) throw new NoSuchElementException("No elements available");
    return t;
  }

  /**
   * Retrieves and removes the head of this buffer, or returns null if empty.
   * @return the head of this buffer or null if empty
   */
  @Override
  public T poll() {
    if (size() == 0) return null;
    T t = get(0);
    headPtr = wrap(headPtr + 1);
    buffer.set(headPtr, null); // to allow removed value to be GC'd
    return t;
  }

  /**
   * Retrieves, but does not remove, the head of this buffer. This method differs from peek only in
   * that it throws an exception if empty.
   * @return the head of this buffer
   */
  @Override
  public T element() {
    T t = peek();
    if (t == null) throw new NoSuchElementException("No elements available");
    return t;
  }

  /**
   * Retrieves, but does not remove, the head of this buffer, or returns null if empty.
   * @return the head of this buffer or null if empty
   */
  @Override
  public T peek() {
    if (size() == 0) return null;
    return get(0);
  }

  @Override
  public int hashCode() {
    int result = capacity();
    result = 31 * result + size();
    for (int i = 0; i < size(); i++) {
      T item = get(i);
      result = 31 * result + (item == null ? 0 : item.hashCode());
    }
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof EvictingRingBuffer)) return false;
    EvictingRingBuffer<T> other = (EvictingRingBuffer<T>) obj;
    if (capacity() != other.capacity()) return false;
    if (size() != other.size()) return false;
    for (int i = 0; i < size(); i++) {
      if (get(i) != other.get(i)) return false;
    }
    return true;
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  private int wrap(int index) {
    int m = index % bufferSize;
    return m < 0 ? m + bufferSize : m;
  }
}

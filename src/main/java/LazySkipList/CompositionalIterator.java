package LazySkipList;

import java.util.NoSuchElementException;

public interface CompositionalIterator<E> {

    /**
     * Returns whether there are more elements to iterate, i.e. whether the
     * iterator is positioned in front of an element.
     * 
     * @return {@code true} if there are more elements, {@code false} otherwise.
     * @see #next
     */
    public boolean hasNext();

    /**
     * Returns the next object in the iteration, i.e. returns the element in
     * front of the iterator and advances the iterator by one position.
     * 
     * @return the next object.
     * @throws NoSuchElementException
     *             if there are no more elements.
     * @see #hasNext
     */
    public E next();

    /**
     * Removes the last object returned by {@code next} from the collection.
     * This method can only be called once after {@code next} was called.
     * 
     * @throws UnsupportedOperationException
     *             if removing is not supported by the collection being
     *             iterated.
     * @throws IllegalStateException
     *             if {@code next} has not been called, or {@code remove} has
     *             already been called after the last call to {@code next}.
     */
    public void remove();

}

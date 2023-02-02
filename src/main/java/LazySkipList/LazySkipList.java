/*
 * Algorithm:
 *   Fine-grained locking skip list.
 *   "A Simple Optimistic Skiplist Algorithm" 
 *   M. Herlihy, Y. Lev, V. Luchangco, N. Shavit 
 *   p.124-138, SIROCCO 2007
 *
 * Code:
 *  Based on example code from:
 *  "The Art of Multiprocessor Programming"
 *  M. Herlihy, N. SHavit
 *  chapter 14.3, 2008
 *
 */

package LazySkipList;

import java.util.Collection;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class LazySkipList implements CompositionalIntSet {

    /** The maximum number of levels */
    final private int maxLevel;
    /** The first element of the list */
    final private Node head;
    /** The last element of the list */
    final private Node tail;

    /** The thread-private PRNG, used for fil(), not for height/level determination. */
        final private static ThreadLocal<Random> s_random = new ThreadLocal<Random>() {
            @Override
            protected synchronized Random initialValue() {
                return new Random();
            }
        };

    private int randomLevel() {
        return Math.min((maxLevel - 1), (RandomLevelGenerator.randomLevel()));
    }

    public LazySkipList() {
        this(31);
    }

    public LazySkipList(final int maxLevel) {
        this.head = new Node(Integer.MIN_VALUE, maxLevel);
        this.tail = new Node(Integer.MAX_VALUE, maxLevel);
        this.maxLevel = maxLevel;
        for (int i = 0; i <= maxLevel; i++) {
            head.next[i] = tail;
        }
    }

    @Override
    public boolean containsInt(final int value) {
        Node[] preds = (Node[]) new Node[maxLevel + 1];
        Node[] succs = (Node[]) new Node[maxLevel + 1];
        int levelFound = find(value, preds, succs);
        return (levelFound != -1 && succs[levelFound].fullyLinked && !succs[levelFound].marked);
    }

    /* The preds[] and succs[] arrays are filled from the maximum level to 0 with the predecessor and successor references for the given key. */
    private int find(final int value, Node[] preds, Node[] succs) {
        int key = value;
        int levelFound = -1;
        Node pred = head;

        for (int level = maxLevel; level >= 0; level--) {
            Node curr = pred.next[level];

            while (key > curr.key) {
                pred = curr;
                curr = pred.next[level];
            }

            if (levelFound == -1 && key == curr.key) {
                levelFound = level;
            }
            preds[level] = pred;
            succs[level] = curr;
        }
        return levelFound;
    }

    @Override
    public boolean addInt(final int value) {
        //int topLevel = randomLevel();
        int topLevel = ThreadLocalRandom.current().nextInt(maxLevel);
        Node[] preds = (Node[]) new Node[maxLevel+ 1];
        Node[] succs = (Node[]) new Node[maxLevel+ 1];

        while (true) {
            /* Call find() to initialize preds and succs. */
            int levelFound = find(value, preds, succs);

            /* If an node is found that is unmarked then return false. */
            if (levelFound != -1) {
                Node nodeFound = succs[levelFound];
                if (!nodeFound.marked) {
                    /* Needs to wait for nodes to become fully linked. */
                    while (!nodeFound.fullyLinked) {}
                    return false;
                }
                /* If marked another thread is deleting it, so we retry. */
                continue;
            }

            int highestLocked = -1;

            try {
                Node pred, succ;
                boolean valid = true;

                /* Acquire locks. */
                for (int level = 0; valid && (level <= topLevel); level++) {
                    pred = preds[level];
                    succ = succs[level];
                    pred.lock.lock();
                    highestLocked = level;
                    valid = !pred.marked && !succ.marked && pred.next[level]==succ;
                }

                /* Must have encountered effects of a conflicting method, so it releases (in the
                 * finally block) the locks it acquired and retries */
                if (!valid) {
                    continue;
                }

                Node newNode = new Node(value, topLevel);
                for (int level = 0; level <= topLevel; level++) {
                    newNode.next[level] = succs[level];
                }
                for (int level = 0; level <= topLevel; level++) {
                    preds[level].next[level] = newNode;
                }
                newNode.fullyLinked = true; // successful and linearization point
                return true;

            } finally {
                for (int level = 0; level <= highestLocked; level++) {
                    preds[level].unlock();
                }
            }

        }

    }

    @Override
    public boolean removeInt(final int value) {
        Node victim = null;
        boolean isMarked = false;
        int topLevel = -1;
        Node[] preds = (Node[]) new Node[maxLevel + 1];
        Node[] succs = (Node[]) new Node[maxLevel + 1];

        while (true) {
            /* Call find() to initialize preds and succs. */
            int levelFound = find(value, preds, succs);
            if (levelFound != -1) {
                victim = succs[levelFound];
            }

            /* Ready to delete if unmarked, fully linked, and at its top level. */
            if (isMarked | (levelFound != -1 && (victim.fullyLinked && victim.topLevel == levelFound && !victim.marked))) {

                /* Acquire locks in order to logically delete. */
                if (!isMarked) {
                    topLevel = victim.topLevel;
                    victim.lock.lock();
                    if (victim.marked) {
                        victim.lock.unlock();
                        return false;
                    }
                    victim.marked = true; // logical deletion
                    isMarked = true;
                }

                int highestLocked = -1;

                try {
                    Node pred, succ;
                    boolean valid = true;

                    /* Acquire locks. */
                    for (int level = 0; valid && (level <= topLevel); level++) {
                        pred = preds[level];
                        pred.lock.lock();
                        highestLocked = level;
                        valid = !pred.marked && pred.next[level]==victim;
                    }

                    /* Pred has changed and is no longer suitable, thus unlock and retries. */
                    if (!valid) {
                        continue;
                    }

                    /* Unlink. */
                    for (int level = topLevel; level >= 0; level--) {
                        preds[level].next[level] = victim.next[level];
                    }
                    victim.lock.unlock();
                    return true;

                } finally {
                    for (int i = 0; i <= highestLocked; i++) {
                        preds[i].unlock();
                    }
                }
            } else {
                return false;
            }
        }
    }

    @Override
    public void fill(final int range, final long size) {
        while (this.size() < size) {
            this.addInt(s_random.get().nextInt(range));
        }
    }

    @Override
    public Object getInt(int value) {
       // TODO
       return null;
    }

    @Override
    public boolean addAll(Collection<Integer> c) {
       // TODO
       return false;
    }

    @Override
    public boolean removeAll(Collection<Integer> c){
       // TODO
       return false;
    }

    @Override
    public int size() {
        int size = 0;
        Node node = head.next[0].next[0];

        while (node != null) {
            node = node.next[0];
            size++;
        }
        return size;
    }

    @Override
    public void clear() {
        for (int i = 0; i <= this.maxLevel; i++) {
            this.head.next[i] = this.tail;
        }
        return;
    }

    @Override
    public String toString() {
       // TODO
       return null;

    }

    @Override
    public Object putIfAbsent(int x, int y) {
       // TODO
       return null;
    }

    private static final class Node {
        final Lock lock = new ReentrantLock();
        final int key;
        final Node[] next;
        volatile boolean marked = false;
        volatile boolean fullyLinked = false;
        private int topLevel;

        public Node(final int value, int height) {
            key = value;
            next = new Node[height + 1];
            topLevel = height;
        }

        public void lock() {
            lock.lock();
        }

        public void unlock() {
            lock.unlock();
        }
    }

}

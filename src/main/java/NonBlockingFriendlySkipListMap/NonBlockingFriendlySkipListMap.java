package NonBlockingFriendlySkipListMap;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The No Hot Spot Non-Blocking Skip List
 * as presented in the paper from Crain, Gramoli and Raynal
 * that appeared at ICDCS 2013.
 * 
 * @author Tyler Crain
 * 
 * @param <K>
 *            The key
 * @param <V>
 *            The value
 */
public class NonBlockingFriendlySkipListMap<K, V> extends AbstractMap<K, V>
		implements CompositionalMap<K, V>, MaintenanceAlg,
		ConcurrentNavigableMap<K, V> {

	/**
	 * If set to true then a maintenance thread is used
	 */
	private static final boolean maintenance = true;

	/**
	 * If set to true, then the maintenance thread will physically remove nodes
	 */
	private static final boolean removeInMainteance = true;

	/**
	 * If set to true then the delete operation will physically remove nodes
	 */
	private static final boolean removeInDelete = true;

	/**
	 * If set to true, then the algorithms will raise the bottom index level if
	 * necessary
	 */
	private static final boolean doBottomLevelRaises = true;

	private static final boolean useFairLocks = false;
	private static final boolean tryLock = true;

	/**
	 * List of all skip lists in the system that should be maintained by the
	 * maintenance thread (there is only one maintenance thread in the system)
	 */
	private static final ConcurrentLinkedQueue<NonBlockingFriendlySkipListMap> skipLists = new ConcurrentLinkedQueue<NonBlockingFriendlySkipListMap>();

	/**
	 * Number of times the bottom indexItem level has been removed
	 */
	private final AtomicInteger bottomLevelRaiseCount = new AtomicInteger();

	/**
	 * Class used to keep track of the counts of the maintenance operations done
	 * 
	 */
	private class MaintVars {
		private volatile int maxHeight = 1;
		private long heightChanges = 0;
		private long tallDeletedCount = 0, totalCount = 0, nonDeleted = 0;
		private long removals = 0;
	}

	/**
	 * Used to decide when to perform the maintenance
	 */
	private final MaintVars vars = new MaintVars();

	/**
	 * The maximum height the skip list can reach
	 */
	private static final int totalHeight = 31;

	/**
	 * The height of the skip list to start
	 */
	private static final int initialHeight = 6;

	static final class ComparableUsingComparator<K> implements Comparable<K> {
		final K actualKey;
		final Comparator<? super K> cmp;

		ComparableUsingComparator(K key, Comparator<? super K> cmp) {
			this.actualKey = key;
			this.cmp = cmp;
		}

		public int compareTo(K k2) {
			return cmp.compare(actualKey, k2);
		}
	}

	private final Comparator<? super K> comparator;

	private Comparable<? super K> comparable(Object key)
			throws ClassCastException {
		if (key == null)
			throw new NullPointerException();
		if (comparator != null)
			return new ComparableUsingComparator<K>((K) key, comparator);
		else
			return (Comparable<? super K>) key;
	}

	/**
	 * This lock is used when maintenance is distributed among application
	 * threads, the thread doing the maintenance must own this lock
	 * 
	 * No longer used!
	 */
	private final ReentrantLock maintLock = new ReentrantLock(useFairLocks);

	/**
	 * Extension of thread class, the maintenance thread is an instance of this
	 * class
	 * 
	 */
	private static class MaintenanceThread extends Thread {

		public void run() {
			doMaintenance();
		}
	}

	/**
	 * Instances of this class make up the upper levels of the skip-list
	 * 
	 * @param <K>
	 *            The key type
	 * @param <V>
	 *            The value type
	 */
	static class Index<K, V> {
		/**
		 * The node at the bottom list level that this Index corresponds to
		 */
		final Node<K, V> node;
		/**
		 * The Index item one level below this one, or null if this is already
		 * the bottom index level
		 */
		final Index<K, V> down;
		/**
		 * The index item to the right, or null if this is the end of this level
		 */
		volatile Index<K, V> right;

		/**
		 * Creates index node with given values.
		 */
		Index(Node<K, V> node, Index<K, V> down, Index<K, V> right) {
			this.node = node;
			this.down = down;
			this.right = right;
		}

		/** Updater for casRight */
		static final AtomicReferenceFieldUpdater<Index, Index> rightUpdater = AtomicReferenceFieldUpdater
				.newUpdater(Index.class, Index.class, "right");

		/**
		 * compareAndSet right field
		 */
		final boolean casRight(Index<K, V> cmp, Index<K, V> val) {
			// If the Index levels of the skip list are maintained by the
			// maintenance, then
			// we don't need synchronization
			if (maintenance) {
				right = val;
				return true;
			}
			return rightUpdater.compareAndSet(this, cmp, val);
		}
	}

	/**
	 * Nodes make up the bottom level of the skip list
	 * 
	 * @param <K>
	 *            The key type
	 * @param <V>
	 *            The value type
	 */
	static final class Node<K, V> {
		final K key;
		volatile V value;
		volatile Node<K, V> next, prev;
		final ReentrantLock lock = new ReentrantLock(useFairLocks);

		final class LevelVars {
			volatile int topLevel = 0;
			boolean updated = false;
			// does this need to be volatile?
			volatile Index<K, V> up = null;
		}

		final LevelVars vars = new LevelVars();

		/**
		 * Creates a new regular node.
		 */
		Node(K key, V value) {
			this.key = key;
			this.value = value;
		}

		/**
		 * Constructor for marker node, used during physical removal It has a
		 * null key, and a value that points to itself
		 */
		private Node() {
			this.key = null;
			this.value = (V) this;
		}

		/**
		 * Creates a marker node
		 * 
		 * @param prev
		 *            the previous node in the list
		 * @param next
		 *            the next node in the list
		 * @return the new marker node
		 */
		static final Node newMarker(Node prev, Node next) {
			Node node = new Node();
			node.prev = prev;
			node.next = next;
			return node;
		}

		/** Updater for casNext */
		static final AtomicReferenceFieldUpdater<Node, Node> nextUpdater = AtomicReferenceFieldUpdater
				.newUpdater(Node.class, Node.class, "next");

		/**
		 * compareAndSet next field
		 */
		final boolean casNext(Node<K, V> cmp, Node<K, V> val) {
			return nextUpdater.compareAndSet(this, cmp, val);
		}

		/** Updater for casValue */
		static final AtomicReferenceFieldUpdater<Node, Object> valueUpdater = AtomicReferenceFieldUpdater
				.newUpdater(Node.class, Object.class, "value");

		/**
		 * compareAndSet value field
		 */
		final boolean casValue(Object cmp, Object val) {
			return valueUpdater.compareAndSet(this, cmp, val);
		}

	}

	/**
	 * Instances of this class are used to point to the beginning of the top and
	 * bottom Index item lists
	 * 
	 * @param <K>
	 *            The key type
	 * @param <V>
	 *            The value type
	 */
	static class HeadPointer<K, V> {
		final Index<K, V> node;
		final int value;

		HeadPointer(Index<K, V> node, int value) {
			this.node = node;
			this.value = value;
		}
	}

	/**
	 * Always the first node in the Node list
	 */
	private final Node<K, V> begin = new Node<K, V>((K) new Object(), null);

	/**
	 * Pointer for the start of the top Index list
	 */
	private volatile HeadPointer<K, V> topStart;
	/**
	 * Pointer for the start of the bottom Index list
	 */
	private volatile HeadPointer<K, V> bottomStart;

	/** Updater for topStart */
	static final AtomicReferenceFieldUpdater<NonBlockingFriendlySkipListMap, HeadPointer> topStartUpdater = AtomicReferenceFieldUpdater
			.newUpdater(NonBlockingFriendlySkipListMap.class,
					HeadPointer.class, "topStart");

	/**
	 * compareAndSet topStart field
	 */
	final boolean casTopStart(HeadPointer<K, V> cmp, HeadPointer<K, V> val) {
		return topStartUpdater.compareAndSet(this, cmp, val);
	}

	/** Updater for bottomStart */
	static final AtomicReferenceFieldUpdater<NonBlockingFriendlySkipListMap, HeadPointer> bottomStartUpdater = AtomicReferenceFieldUpdater
			.newUpdater(NonBlockingFriendlySkipListMap.class,
					HeadPointer.class, "bottomStart");

	/**
	 * compareAndSet topStart field
	 */
	final boolean casBottomStart(HeadPointer<K, V> cmp, HeadPointer<K, V> val) {
		return bottomStartUpdater.compareAndSet(this, cmp, val);
	}

	// For maintenance thread
	/**
	 * Used to stop the maintenance loop
	 */
	static volatile boolean stop = false;
	/**
	 * Instance of the maintenance thread This is static and atomic because we
	 * have exactly one maintenance thread, even if we have multiple skip lists
	 */
	private static AtomicReference<MaintenanceThread> mainThd = new AtomicReference<MaintenanceThread>(
			null);

	// For when maintenance is run by the application threads
	/**
	 * How often the thread should try to perform maintenance
	 */
	private final double maintPercentage;
	/**
	 * If the maintenance should be done by application threads
	 */
	private final boolean seperateMaint;
	/**
	 * Thread local random used to check if it should try to do maintenance when
	 * maintenance is done by application threads
	 */
	final private static ThreadLocal<Random> s_random = new ThreadLocal<Random>() {
		@Override
		protected synchronized Random initialValue() {
			return new Random();
		}
	};

	/**
	 * Array of Index pointers This array is used when a node's height is
	 * raised, it keeps track of the previous item in the list at each level for
	 * where the new Index item will be inserted for this node
	 */
	final private ThreadLocal<Index<K, V>[]> thdLocalPrevArray = new ThreadLocal<Index<K, V>[]>() {
		@Override
		protected synchronized Index<K, V>[] initialValue() {
			return (Index<K, V>[]) new Index[totalHeight + 1];
		}
	};

	// Constructors
	public NonBlockingFriendlySkipListMap() {
		this(null, 0);
	}

	public NonBlockingFriendlySkipListMap(double maintPercentage) {
		this(null, maintPercentage);
	}

	/**
	 * Creates a new instance of the skip list
	 * 
	 * @param comparator
	 *            for comparing
	 * @param maintPercentage
	 *            if set to 0 then we have seperate maintenance
	 */
	public NonBlockingFriendlySkipListMap(Comparator<? super K> comparator,
			double maintPercentage) {

		this.comparator = comparator;
		this.maintPercentage = maintPercentage;

		initialize();

		if (maintenance) {
			if (maintPercentage == 0) {
				this.seperateMaint = true;
			} else {
				this.seperateMaint = false;
			}
			this.startMaintenance();
		} else {
			seperateMaint = false;
		}
	}

	/**
	 * Initializes an empty skip list
	 */
	private void initialize() {
		values = null;
		begin.next = null;
		begin.vars.topLevel = totalHeight;
		// begin.vars.bottomLevel = 0;

		// The following lines create a new node of maximum initial height
		// used as the "root" node in the list, and points the beginning
		// pointers to this node and its index
		ArrayList<Index<K, V>> beginList = new ArrayList<Index<K, V>>(
				initialHeight);
		Index<K, V> nextBegin = null, prevBegin = new Index<K, V>(begin, null,
				null);
		bottomStart = new HeadPointer<K, V>(prevBegin, 0);
		beginList.add(0, nextBegin);
		for (int i = 1; i < initialHeight; i++) {
			nextBegin = new Index<K, V>(begin, prevBegin, null);
			beginList.add(i, nextBegin);
			prevBegin = nextBegin;
		}
		topStart = new HeadPointer<K, V>(nextBegin, initialHeight - 1);
		begin.vars.up = bottomStart.node;

		// Add this skip list to the list of skip lists to be maintained by
		// the maintenance thread
		if (maintenance)
			skipLists.add(this);
	}

	void finishCount2(int nodesTraversed) {
		Vars vars = counts.get();
		vars.nodesTraversed += nodesTraversed;
	}

	void finishCount1(int nodesTraversed) {
		Vars vars = counts.get();
		vars.getCount++;
		vars.nodesTraversed += nodesTraversed;
	}

	@Override
	public V get(final Object kkey) {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		int nodesTraversed = 0;
		Comparable<? super K> key = comparable(kkey);
		// Get the suspected node previous to the one being searched for
		Node<K, V> prev = getPrevFast(key, top.node, top.value, bottom.value);
		for (;;) {
			Node<K, V> next = prev.next;
			if (TRAVERSAL_COUNT) {
				nodesTraversed++;
			}
			// Didn't find the node
			if (next == null) {
				if (TRAVERSAL_COUNT) {
					finishCount2(nodesTraversed);
				}
				return null;
			}
			K nextKey = next.key;
			int c;
			if (nextKey == null)
				// null key means marker, keep traversing
				c = 1;
			else
				c = key.compareTo(nextKey);
			if (c == 0) {
				// found the node
				if (TRAVERSAL_COUNT) {
					finishCount2(nodesTraversed);
				}
				V val = next.value;
				// check if it has been marked deleted
				if (val != next) {
					return val;
				}
				return null;
			} else if (c < 0) {
				if (TRAVERSAL_COUNT) {
					finishCount2(nodesTraversed);
				}
				return null;
			}
			// Not at the correct node so continue traversal
			prev = getPrevNode(key, next, false);
		}
	}

	@Override
	public boolean containsKey(Object key) {
		if (this.get(key) == null) {
			return false;
		}
		return true;
	}

	@Override
	public V putIfAbsent(K kkey, V value) {
		if (!lockFree) {
			return insertLocking(kkey, value, false);
		}
		return insertLockFree(kkey, value, false);
	}

	@Override
	public V put(K kkey, V value) {
		// TODO This is currently just putifabsent!
		if (!lockFree) {
			return insertLocking(kkey, value, true);
		}
		return insertLockFree(kkey, value, true);
	}

	/**
	 * Lock free node insertion
	 * 
	 * @param kkey
	 *            key to insert
	 * @param value
	 *            value to insert
	 * @param put
	 *            if false performs a put-if-absent, otherwise just a put
	 * @return null if no node with key existed, otherwise the value of the node
	 *         with the key
	 */
	private V insertLockFree(K kkey, V value, boolean put) {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		Comparable<? super K> key = comparable(kkey);
		Node<K, V> prev = null;
		Node<K, V> newNode = null;
		if (!maintenance) {
			// if there is no maintenance then we have to raise the node within
			// the insert
			prev = getPrev(key, topStart.node, top.value, bottom.value);
		} else {
			prev = getPrevFast(key, topStart.node, top.value, bottom.value);
		}
		int c;

		for (;;) {
			Node<K, V> next = prev.next;
			if (next == null)
				// end of the list
				c = -1;
			else {
				K nextKey = next.key;
				if (nextKey == null) {
					// marker, can't stop traversal here
					c = 1;
				} else
					c = key.compareTo(nextKey);
			}
			if (c == 0) {
				// found the node
				V val = next.value;
				// loop trying to finish the operation, if the value points to
				// the node,
				// then it has been physically removed, so exit loop and
				// continue traversal
				while (val != next) {
					if (val != null) {
						// node is not marked deleted
						if (put) {
							// if a put, then update the value
							if (next.casValue(val, value)) {
								return val;
							}
						} else {
							// not a put so just return the value
							return val;
						}
					} else {
						// node is marked deleted, undelete it!
						if (next.casValue(val, value))
							return null;
					}
					val = next.value;
				}
			} else if (c < 0) {
				// didn't find the key, so insert a new node
				// but only do it if we are not at a marker node
				if (prev.value != prev && prev.key != null) {
					next = prev.next;
					if (newNode == null) {
						newNode = new Node<K, V>(kkey, value);
					}
					newNode.prev = prev;
					newNode.next = next;
					if (prev.casNext(next, newNode)) {
						if (next != null) {
							next.prev = newNode;
						}
						if (!maintenance) {
							// no maintenance, so must raise the node here
							raiseLevels(key, newNode, top, bottom.value);
						}
						return null;
					}
				}
			}
			// We were not at the right prev node, so continue traversal!
			// Will do helping in here
			prev = getPrevNode(key, prev, true);
		}
	}

	/**
	 * Lock based node insertion
	 * 
	 * @param kkey
	 *            key to insert
	 * @param value
	 *            value to insert
	 * @param put
	 *            if false performs a put-if-absent, otherwise just a put
	 * @return null if no node with key existed, otherwise the value of the node
	 *         with the key
	 */
	public V insertLocking(K kkey, V value, boolean put) {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		Comparable<? super K> key = comparable(kkey);
		Node<K, V> prev = null;
		if (!maintenance) {
			// if there is no maintenance then we have to raise the node within
			// the insert
			prev = getPrev(key, topStart.node, top.value, bottom.value);
		} else {
			prev = getPrevFast(key, topStart.node, top.value, bottom.value);
		}
		int c;

		for (;;) {
			Node<K, V> next = prev.next;
			if (next == null)
				// end of the list
				c = -1;
			else {
				K nextKey = next.key;
				if (nextKey == null)
					// continue traversal
					c = 1;
				else
					c = key.compareTo(nextKey);
			}
			if (c == 0) {
				// found a node with the key
				V val = next.value;
				if (val != next) {
					if (val != null && !put)
						// return immediately if the node exists and not marked
						// deleted
						return val;
					// lock the node
					next.lock.lock();
					// ensure it hasn't been physically removed
					if (next.value != next) {
						val = next.value;
						// check if marked deleted
						if (val != null) {
							if (put) {
								next.value = value;
							}
							next.lock.unlock();
							return val;
						}
						// mark it undeleted
						next.value = value;
						next.lock.unlock();
						return null;
					}
					next.lock.unlock();
				}
			} else if (c < 0) {
				// insert a new node
				// lock the previous node
				prev.lock.lock();
				// ensure the node hasn't been physically removed
				if (prev.value != prev) {
					next = prev.next;
					// ensure that a node hasn't been concurrently inserted in
					// front of us
					if (next == null)
						c = -1;
					else {
						K nextKey = next.key;
						if (nextKey == null)
							c = 1;
						else
							c = key.compareTo(nextKey);
					}
					if (c < 0) {
						// we are still in the correct location, so insert a new
						// node
						Node<K, V> newNode = new Node<K, V>(kkey, value);
						newNode.prev = prev;
						newNode.next = next;
						if (next != null) {
							next.prev = newNode;
						}
						prev.next = newNode;
						prev.lock.unlock();
						if (!maintenance) {
							// no maintenance, so must raise the node here
							raiseLevels(key, newNode, top, bottom.value);
						}
						return null;
					}
				}
				prev.lock.unlock();
			}
			// We were not at the right prev node, so continue traversal!
			prev = getPrevNode(key, prev, true);
		}
	}

	@Override
	public V remove(final Object kkey) {
		if (!lockFree) {
			return removeLocking(kkey);
		}
		return removeLockFree(kkey);
	}

	/**
	 * Lock based deletion
	 * 
	 * @param kkey
	 *            the key to delete
	 * @return null if no node with key was found, otherwise the value of the
	 *         node that was deleted
	 */
	private V removeLocking(final Object kkey) {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		Comparable<? super K> key = comparable(kkey);
		// Find the previous node in the bottom list
		Node<K, V> prev = getPrevFast(key, top.node, top.value, bottom.value);
		for (;;) {
			Node<K, V> next = prev.next;
			// reached the end of the list
			if (next == null)
				return null;
			K nextKey = next.key;
			int c;
			if (nextKey == null)
				// continue traversal
				c = 1;
			else
				c = key.compareTo(nextKey);
			if (c == 0) {
				// found a node with the key
				V val = next.value;
				if (val == null || val == next)
					// it has been deleted
					return null;
				// lock the node
				next.lock.lock();
				val = next.value;
				if (val == null || val == next) {
					// concurrent deletion
					next.lock.unlock();
					return null;
				}
				// Mark deleted
				next.value = null;
				if (removeInDelete) {
					if (!removeInMainteance) {
						// no removals are done in maintenance, since removals
						// are best effort let us try
						// to remove this node as well as other marked deleted
						// nodes connected to this node
						removeMaintLoop(prev, next, bottomStart.value);
					} else {
						// removals are done also in maintenance, so just try to
						// remove this node
						removeMaint(prev, next, bottomStart.value);
					}

				} else {
					next.lock.unlock();
				}
				return val;
			} else if (c < 0)
				// found no node with the key being searched for
				return null;
			// Not at the correct node so continue traversal
			prev = getPrevNode(key, next, false);

		}
	}

	/**
	 * Lock-free deletion
	 * 
	 * @param kkey
	 *            the key to delete
	 * @return null if no node with key was found, otherwise the value of the
	 *         node that was deleted
	 */
	private V removeLockFree(final Object kkey) {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		Comparable<? super K> key = comparable(kkey);
		// find the previous node in the list
		Node<K, V> prev = getPrevFast(key, top.node, top.value, bottom.value);
		for (;;) {
			Node<K, V> next = prev.next;
			if (next == null)
				// reached the end of the list without finding the key
				return null;
			K nextKey = next.key;
			int c;
			if (nextKey == null)
				// marker, continue traversal
				c = 1;
			else
				c = key.compareTo(nextKey);
			if (c == 0) {
				// found a node with the key
				// loop on this node, until the operation is finished, or is
				// physically removed concurrently
				while (true) {
					V val = next.value;
					if (val == null || val == next)
						return null;
					// Mark deleted
					if (next.casValue(val, null)) {
						if (removeInDelete) {
							if (!removeInMainteance) {
								// no removals are done in maintenance, since
								// removals are best effort let us try
								// to remove this node as well as other marked
								// deleted nodes connected to this node
								removeMaintLoopLockFree(prev, next,
										bottomStart.value);
							} else {
								// removals are done also in maintenance, so
								// just try to remove this node
								removeMaintLockFree(prev, next,
										bottomStart.value);
							}
						}
						return val;
					}
				}
			} else if (c < 0)
				return null;
			// Not at the correct node so continue traversal
			prev = getPrevNode(key, next, false);

		}
	}

	/**
	 * 
	 * Traverses the skip-list for the node with the given key starting from the
	 * given Index item, is "fast" because it does not keep track of the
	 * locations from each level where the search moved down to the level below
	 * 
	 * @param key
	 *            the key to search for
	 * @param prev
	 *            the Index to start the search from
	 * @param top
	 *            the level that is search started from
	 * @param bottom
	 *            the bottom index level to stop on
	 * @return the previous Node from the bottom level list of the key being
	 *         searched for
	 */
	private Node<K, V> getPrevFast(Comparable<? super K> key, Index<K, V> prev,
			int top, int bottom) {
		int c = 0;
		int nodesTraversed = 0;
		Index<K, V> next;
		for (;;) {
			// Inner for loop traverses a single Index list level
			for (;;) {
				next = prev.right;
				if (TRAVERSAL_COUNT) {
					nodesTraversed++;
				}
				if (next == null)
					// Reached the end
					break;

				K nextKey = next.node.key;
				if (nextKey == null)
					c = 1;
				else
					c = key.compareTo(nextKey);

				if (c <= 0)
					// Found a node with a bigger key
					break;
				prev = next;
			}
			if (next != null && c == 0) {
				// Immediately stop the Index traversal if a node with key was
				// found
				// Continue the traversal on the Node list level
				if (TRAVERSAL_COUNT) {
					finishCount1(nodesTraversed);
				}
				return getPrevNode(key, next.node.prev, false);
			}
			if (top == bottom)
				// Reached the bottom Index list level
				break;
			top--;
			next = prev.down;
			if (next == null)
				// Reached the bottom Index list level
				break;
			prev = next;
			if (TRAVERSAL_COUNT) {
				nodesTraversed++;
			}
		}
		if (TRAVERSAL_COUNT) {
			finishCount1(nodesTraversed);
		}
		// Continue the traversal on the Node list level
		return getPrevNode(key, prev.node, false);
	}

	/**
	 * 
	 * Traverses the skip-list for the node with the given key starting from the
	 * given Index item, is not "fast" because it keeps track of the locations
	 * from each level where the search moved down to the level below in the
	 * thdLocalPrevArray
	 * 
	 * @param key
	 *            the key to search for
	 * @param prev
	 *            the Index to start the search from
	 * @param top
	 *            the level that is search started from
	 * @param bottom
	 *            the bottom index level to stop on
	 * @return the previous Node from the bottom level list of the key being
	 *         searched for
	 */
	private Node<K, V> getPrev(Comparable<? super K> key, Index<K, V> prev,
			int top, int bottom) {

		Index<K, V>[] array = thdLocalPrevArray.get();
		for (;;) {
			for (;;) {
				Index<K, V> next = prev.right;
				if (next == null)
					break;
				int c;
				K nextKey = next.node.key;
				if (nextKey == null)
					c = 1;
				else
					c = key.compareTo(nextKey);
				if (c <= 0)
					break;
				prev = next;
			}
			// Store the location in the thdLocalPrevArray
			array[top] = prev;
			if (top == bottom)
				break;
			top--;
			prev = prev.down;
		}
		return getPrevNode(key, prev.node, true);
	}

	/**
	 * Traverses the bottom list level looking for a node with key
	 * 
	 * @param key
	 *            the key to search for
	 * @param prev
	 *            the node to start the traversal from
	 * @param isInsert
	 *            if this was called from within an insert operation
	 * @return the node in the list previous to the one that has (or would have)
	 *         key
	 */
	private Node<K, V> getPrevNode(Comparable<? super K> key, Node<K, V> prev,
			boolean isInsert) {
		int c = 0;
		int nodesTraversed = 0;
		for (;;) {
			Node<K, V> next = prev.next;
			if (TRAVERSAL_COUNT) {
				nodesTraversed++;
			}
			if (next != null) {
				K nextKey = next.key;
				if (nextKey == null)
					c = 1;
				else
					c = key.compareTo(nextKey);
			}
			if (next == null || c <= 0) {
				// The reason for the additional check when doing an insert, is
				// that the previous node
				// must be physically in the list before performing adding a new
				// node after it
				if (isInsert) {
					if (prev.value == prev) {
						// the node has been removed, travel backwards until
						// back at a node not physically removed
						while (prev.value == prev) {
							prev = prev.prev;
							if (TRAVERSAL_COUNT) {
								nodesTraversed++;
							}
						}
						if (lockFree) {
							// In lock free need to help remove the node, to
							// ensure progress
							helpRemoval(prev, prev.next);
						}
						continue;
					}
				}
				if (lockFree) {
					helpRemoval(prev, prev.next);
				}
				if (TRAVERSAL_COUNT) {
					finishCount1(nodesTraversed);
				}
				return prev;
			} else {
				prev = next;
			}
		}
	}

	/**
	 * Locks prev or neither, node should be passed in already locked
	 * 
	 * @param prev
	 *            The node to lock
	 * @param node
	 *            This node should already be locked
	 * @return true if the nodes were locked successfully, false otherwise in
	 *         which case both nodes are unlocked
	 */
	private static final boolean lock(Node prev, Node node) {
		if (tryLock) {
			if (!prev.lock.tryLock()) {
				node.lock.unlock();
				return false;
			}
			return true;
		} else {
			prev.lock.lock();
			return true;
		}
	}

	/**
	 * Locks a single node
	 * 
	 * @param node
	 *            The node to lock
	 * @return true if successful, false otherwise
	 */
	private static final boolean lockSingle(Node node) {
		if (tryLock) {
			if (!node.lock.tryLock()) {
				return false;
			}
			return true;
		} else {
			node.lock.lock();
			return true;
		}
	}

	/**
	 * Lock based physical removal (prev and node must be locked when this is
	 * called)
	 * 
	 * @param prev
	 *            the node in the list prior to the one to be removed
	 * @param node
	 *            the node to be removed
	 * @return true if successful, false otherwise
	 */
	private boolean doRemoval(Node<K, V> prev, Node<K, V> node) {

		// Ensure both the nodes haven't already been removed, that node is
		// marked deleted
		// and that prev is actually the node prior to node
		if (prev.value == prev || node.value == node || node.value != null
				|| prev.next != node) {
			return false;
		}
		// physical removal
		Node<K, V> tmp = node.next;
		if (tmp != null)
			tmp.prev = prev;
		prev.next = tmp;
		node.value = (V) node;
		return true;
	}

	/**
	 * Checks to see if the node has a 0 height so it can be removed
	 * 
	 * @param node
	 *            The node to check
	 * @param bottomLevel
	 *            The current bottom list level
	 * @return true if node is safe for removal, false otherwise
	 */
	private static final boolean checkHeightRemoval(Node node, int bottomLevel) {
		return (node.vars.topLevel - 1 < bottomLevel);
	}

	/**
	 * Tries to physically remove node
	 * 
	 * @param prev
	 *            The node just prior to the node to be removed in the list
	 * @param node
	 *            The node to be removed from the list, this node should be
	 *            locked
	 * @return true if the removal was successful, false otherwise
	 */
	private boolean removeMaint(Node<K, V> prev, Node<K, V> node,
			int bottomLevel) {
		// Check to see if this is a valid node to remove
		if (node.value != null || !checkHeightRemoval(node, bottomLevel)
				|| prev.value == prev || prev.next != node) {
			node.lock.unlock();
			return false;
		}
		// lock the nodes
		if (!lock(prev, node))
			return false;

		// perform the physical removal
		doRemoval(prev, node);

		if (STRUCT_MODS) {
			Vars vars = counts.get();
			vars.structMods++;
		}

		prev.lock.unlock();
		node.lock.unlock();
		return true;
	}

	/**
	 * Tries to physically remove node in a lock free manner
	 * 
	 * @param prev
	 *            The node just prior to the node to be removed in the list
	 * @param node
	 *            The node to be removed from the list
	 * @return true if the removal was successful, false otherwise
	 */
	private boolean removeMaintLockFree(Node<K, V> prev, Node<K, V> node,
			int bottomLevel) {
		// Check to see if it is a valid node to remove
		if (node.value != null || !checkHeightRemoval(node, bottomLevel)
				|| prev.value == prev || prev.next != node) {
			return false;
		}
		// Mark the node for removal
		node.casValue(null, node);

		if (node.value != node) {
			return false;
		}

		// Finish the removal
		if (!helpRemoval(prev, node))
			return false;

		// Update the prev pointer
		// Synchronization not used here as prev pointer just needs to point
		// back to the
		// list, not the exact prev node
		Node<K, V> prevNext = prev.next;
		if (prevNext != null)
			prevNext.prev = prev;

		if (STRUCT_MODS) {
			Vars vars = counts.get();
			vars.structMods++;
		}

		return true;
	}

	/**
	 * Tries to physically remove node in a lock free manner, plus any marked
	 * nodes immediately previous to this node
	 * 
	 * @param prev
	 *            The node just prior to the node to be removed in the list
	 * @param node
	 *            The node to be removed from the list
	 * @return true
	 */
	private boolean removeMaintLoopLockFree(Node<K, V> prev, Node<K, V> node,
			int bottomLevel) {
		while (removeMaintLockFree(prev, node, bottomLevel)) {
			node = prev;
			prev = prev.prev;
		}
		return true;
	}

	/**
	 * Tries to finish the removal of a node who has been marked for removal in
	 * a lock-free manner
	 * 
	 * @param prev
	 *            The node just prior to the node to be removed in the list
	 * @param node
	 *            The node to be removed from the list
	 * @return true if successful, false otherwise
	 */
	public boolean helpRemoval(Node<K, V> prev, Node<K, V> node) {
		Node<K, V> next;

		// Ensure the previous node is not a marker
		if (prev.key == null || prev.next != node)
			return false;
		// Ensure the node is not a marker
		if (node == null || node.value != node)
			return false;

		next = node.next;
		while (next == null || next.key != null) {
			// Insert a marker after the node
			node.casNext(next, Node.newMarker(node, next));
			next = node.next;
		}

		// remove the node and the marker
		if (prev.casNext(node, next.next))
			return true;

		return false;
	}

	/**
	 * Loops, physically removing nodes until a removal fails, starts at node
	 * moving backwards in the list. Only removes nodes with height 0 that are
	 * marked as deleted. Does hand over hand locking.
	 * 
	 * @param prev
	 *            The node just prior to the node to be removed in the list
	 * @param node
	 *            The node to be removed from the list, this node should be
	 *            locked
	 * @return true if the removal was successful, false otherwise
	 */
	private boolean removeMaintLoop(Node<K, V> prev, Node<K, V> node,
			int bottomLevel) {
		// ensure the node is correct for removal
		if (node.value != null || !checkHeightRemoval(node, bottomLevel)
				|| prev.value == prev || prev.next != node) {
			node.lock.unlock();
			return false;
		}
		if (!lock(prev, node))
			return false;

		// loop backwards removing nodes until failed
		while (true) {
			if (doRemoval(prev, node)) {
				node.lock.unlock();

				if (STRUCT_MODS) {
					Vars vars = counts.get();
					vars.structMods++;
				}

				node = prev;
				prev = prev.prev;

				// check the node prior is ok for removal
				if (node.value != null
						|| !checkHeightRemoval(node, bottomLevel)
						|| prev.value == prev || prev.next != node) {
					node.lock.unlock();
					return true;
				}
				if (!lock(prev, node))
					return true;
			} else {
				break;
			}
		}
		prev.lock.unlock();
		node.lock.unlock();
		return true;
	}

	/**
	 * Does a full traversal of the list, attempting to remove nodes of height 0
	 * that are marked deleted
	 * 
	 * No longer used!
	 */
	private void removeTraversal() {
		Node<K, V> prev, next;
		int bottomLevel = bottomStart.value;
		prev = begin;
		next = prev.next;
		while (next != null) {
			if (checkHeightRemoval(next, bottomLevel) && next.value == null) {
				if (!lockFree) {
					if (lockSingle(next)) {
						removeMaint(prev, next, bottomLevel);
					}
				} else {
					removeMaintLockFree(prev, next, bottomLevel);
				}
			}
			prev = next;
			next = next.next;
		}
	}

	/**
	 * Checks to see if the bottom level should be raised, happens based on some
	 * function of the number of tall, marked deleted nodes and the total number
	 * of nodes, this is called by the maintenance thread
	 * 
	 * @return true if the bottom level should be raised, false otherwise
	 */
	private final boolean checkShouldRaiseBottomLevelMaint(MaintVars vars) {
		if (vars.tallDeletedCount > vars.nonDeleted * 10)
			return true;
		return false;
	}

	/**
	 * Checks to see if the bottom level should be raised and does so if
	 * necessary, happens based on some function of the number of tall, marked
	 * deleted nodes, this is called by any thread
	 * 
	 * No longer used!
	 * 
	 * @return true if the bottom level was raised, false otherwise
	 */
	private final boolean checkShouldRaiseBottomLevel(HeadPointer<K, V> bottom) {
		if (s_random.get().nextInt(10000) < 9999)
			return false;

		MaintVars vars = new MaintVars();
		Node<K, V> next = begin.next;
		vars.tallDeletedCount = 0;
		vars.totalCount = 0;
		vars.nonDeleted = 0;

		int bottomLevel = bottom.value;
		while (next != null) {

			if (next.value == null && !checkHeightRemoval(next, bottomLevel)) {
				vars.tallDeletedCount++;
			}
			if (next.value != next && next.value != null) {
				vars.nonDeleted++;
			}
			vars.totalCount++;
			next = next.next;
		}

		if (checkShouldRaiseBottomLevelMaint(vars)) {
			this.increaseBottomStart(bottom);
			return true;
		}
		return false;
	}

	/**
	 * Does a full traversal of the skip list "Mixed" because is does all the
	 * following: removing nodes, raising node levels, and removing bottom Index
	 * level if conditions are met
	 */
	private void mixedTraversal() {
		HeadPointer<K, V> bottom = bottomStart, top = topStart;

		// Check to see if the bottom Index level should be removed (i.e. there
		// are too many tall, marked deleted nodes)
		if ((removeInDelete || removeInMainteance) && doBottomLevelRaises
				&& checkShouldRaiseBottomLevelMaint(vars)) {
			// Remove the bottom index level
			increaseBottomStart(bottom);
			bottom = bottomStart;
		}

		// Traverse the Node list
		boolean status = nodeLevelTraversal(bottom);
		Index<K, V>[] array = thdLocalPrevArray.get();
		// Get the first Index item in ever list
		initializeIndexArray(top, bottom);
		int currentLevel = bottom.value;

		// Traverse each index level until nodes don't need to be made any
		// higher
		while (status) {
			// First make sure we have an index level above
			if (top.value <= currentLevel + 1) {
				// if not, add another level
				if (!increaseTopStart(top))
					break;
				top = topStart;
				array[top.value] = top.node;
			}
			// Traverse the index level, raising
			status = indexLevelTraversal(array[currentLevel + 1],
					array[currentLevel], currentLevel);
			currentLevel++;
		}
	}

	/**
	 * Checks if a node should have it's level raised
	 * 
	 * @param node
	 *            The node to check
	 * @param bottomLevel
	 *            The level to check
	 * @return true if the node should raise its level, false otherwise
	 */
	private static final boolean checkShouldRaiseNode(Node node, int bottomLevel) {
		Node prev = node.prev, next = node.next;
		if (prev == null || next == null)
			return false;
		// Check the heights of the neighboring nodes, only raise the height if
		// both its neighbors have height of 1
		if (!(prev.vars.topLevel - 1 < bottomLevel
				&& next.vars.topLevel - 1 < bottomLevel && node.vars.topLevel - 1 < bottomLevel))
			return false;
		if (maintenance) {
			return true;
		} else {
			// In the case of no maintenance, only raise nodes if additionally
			// its neighbors neighbors have height 1
			prev = prev.prev;
			next = next.next;
			if (prev == null || next == null)
				return false;
			return (prev.vars.topLevel - 1 < bottomLevel && next.vars.topLevel - 1 < bottomLevel);
		}
	}

	/**
	 * Checks if a node should have it's level raised, looking from an index
	 * level
	 * 
	 * @param prevIndex
	 *            The index prior to this node in the list
	 * @param nodeIndex
	 *            The node to check
	 * @param bottomLevel
	 *            The level to check
	 * @return true if the node should raise its level, false otherwise
	 */
	private static final boolean checkShouldRaiseIndex(Index prevIndex,
			Index nodeIndex, int bottomLevel) {
		Index nextIndex = nodeIndex.right;
		if (prevIndex == null || nextIndex == null)
			return false;
		Node prev = prevIndex.node, next = nextIndex.node;
		return (prev.vars.topLevel - 1 <= bottomLevel
				&& next.vars.topLevel - 1 <= bottomLevel && nodeIndex.node.vars.topLevel - 1 <= bottomLevel);
	}

	/**
	 * Traverses the given index leveling raising items up one level if needed
	 * 
	 * @param above
	 *            The start of the upper level list
	 * @param current
	 *            The start of the lower level list
	 * @param currentLevel
	 *            The number of the current level
	 * @return true if the level should be raised again, false otherwise
	 */
	private boolean indexLevelTraversal(Index<K, V> above, Index<K, V> current,
			int currentLevel) {
		boolean raised = false;
		Index<K, V> prevAbove = above, prev = current, next = current.right;
		Node<K, V> nextNode;
		if (prevAbove == null)
			// Should not happen
			System.out.println("SSSSSSSS" + currentLevel);
		// Traverse the level
		while (next != null) {
			nextNode = next.node;
			// Ensure the node should be raised
			if (nextNode.value != nextNode
					&& checkShouldRaiseIndex(prev, next, currentLevel)) {
				// Raise the node
				prevAbove = raiseSingleListLevel(comparable(nextNode.key),
						prevAbove, next, nextNode);
				nextNode.vars.topLevel++;
				raised = true;
			}
			prev = next;
			next = next.right;
		}
		return raised;
	}

	/**
	 * Traverses the bottom level of the skip list, removing nodes and raising
	 * the level of nodes by one if necessary
	 * 
	 * @param bottom
	 *            The start of the bottom index level
	 * @return true if the levels should be raised again, false otherwise
	 */
	private boolean nodeLevelTraversal(HeadPointer<K, V> bottom) {
		Index<K, V> prevIndex = bottom.node;
		Node<K, V> prev = begin, next = begin.next;
		int bottomLevel = bottom.value;
		boolean raised = false;
		vars.tallDeletedCount = 0;
		vars.totalCount = 0;
		vars.nonDeleted = 0;

		while (next != null) {

			// check if marked deleted
			if (next.value == null) {
				// Check for removal
				if (checkHeightRemoval(next, bottomLevel)) {
					if (removeInMainteance) {
						if (!lockFree) {
							if (next.value != next && lockSingle(next)) {
								if (removeMaint(prev, next, bottomLevel))
									vars.removals++;
							}
						} else {
							if (removeMaintLockFree(prev, next, bottomLevel))
								vars.removals++;
						}
					}
				} else {
					// Number of marked deleted nodes
					vars.tallDeletedCount++;
				}
			}
			// if not marked deleted, check if it should be raised
			else if (next.value != next
					&& checkShouldRaiseNode(next, bottomLevel)) {
				prevIndex = raiseSingleListLevel(comparable(next.key),
						prevIndex, null, next);
				next.vars.topLevel = bottomLevel + 1;
				raised = true;
			}
			if (next.value != next && next.value != null) {
				// number of nodes not marked deleted
				vars.nonDeleted++;
			}
			// total number of nodes
			vars.totalCount++;
			prev = next;
			next = next.next;
		}
		return raised;
	}

	/**
	 * Does a full traversal of the list, setting the level of newly inserted
	 * nodes
	 * 
	 * No longer used!
	 */
	private void raiseTraversal() {
		HeadPointer<K, V> top = topStart, bottom = bottomStart;
		initializeIndexArray(top, bottom);
		Node<K, V> next = begin.next;
		while (next != null) {
			if (!next.vars.updated && next.value != null) {
				raiseLevels(comparable(next.key), next, top, bottom.value);
				next.vars.updated = true;
			}
			next = next.next;
		}
	}

	/**
	 * Returns a random level for inserting a new node. Hardwired to k=1, p=0.5,
	 * max 31 (see above and Pugh's "Skip List Cookbook", sec 3.4).
	 * 
	 * This uses the simplest of the generators described in George Marsaglia's
	 * "Xorshift RNGs" paper. This is not a high-quality generator but is
	 * acceptable here.
	 */
	private int randomLevel() {
		//return Math.min((totalHeight - 1), (RandomLevelGenerator.randomLevel()));
		return ThreadLocalRandom.current().nextInt(totalHeight);
	}

	/**
	 * Raises a newly created node up to some random list level, called by the
	 * insert operation This is only done when there is no maintenance
	 * 
	 * @param key
	 *            The comparable key
	 * @param node
	 *            The newly created node
	 * @param top
	 *            The maximum list level to go to
	 * @param bottom
	 *            The bottom list level to start from
	 */
	private void raiseLevels(Comparable<? super K> key, Node<K, V> node,
			HeadPointer<K, V> top, int bottom) {

		if (!checkShouldRaiseNode(node, bottom))
			return;

		int height = randomLevel();
		height--;
		int maxHeight = top.value - bottom + 1;
		if (height > maxHeight) {
			increaseTopStart(top);
		}
		height = Math.min(height, top.value - bottom + 1);

		if (height > 0) {
			// Do the raising
			node.vars.topLevel = bottom + height;
			Index<K, V> prevIndex = null;
			// The thdLocalPrevArray has already been filled during the
			// traversal of the insert operation
			Index<K, V>[] array = thdLocalPrevArray.get();
			while (top.value >= bottom && height > 0) {
				prevIndex = raiseSingleListLevel(key, array[bottom], prevIndex,
						node);
				bottom++;
				height--;
			}
		}
	}

	/**
	 * Increases the value of top start by 1 if the head pointer prev has not
	 * changed, this is to add a new Index to the top of the skip list
	 * 
	 * @param prev
	 *            The previous top head pointer
	 * @return true if successful, false otherwise
	 */
	private boolean increaseTopStart(HeadPointer<K, V> prev) {
		if (topStart == prev && prev.value + 1 < totalHeight) {
			return casTopStart(prev, new HeadPointer<K, V>(new Index<K, V>(
					begin, prev.node, null), prev.value + 1));
		}
		return false;
	}

	/**
	 * Raises the level of the bottom level by one This is to remove the Index
	 * level at the bottom
	 * 
	 * @param prev
	 *            The previous top head pointer
	 * @return true if successful, false otherwise
	 */
	private boolean increaseBottomStart(HeadPointer<K, V> prev) {
		HeadPointer<K, V> top = topStart;
		if (bottomStart == prev && top.value > prev.value) {
			Index<K, V> nextIndex, prevIndex;
			nextIndex = top.node;
			prevIndex = top.node;
			// Find the Index level that is just above the one to get rid of
			while (nextIndex != prev.node) {
				prevIndex = nextIndex;
				nextIndex = nextIndex.down;
			}
			if (casBottomStart(prev, new HeadPointer<K, V>(prevIndex,
					prev.value + 1))) {
				bottomLevelRaiseCount.getAndIncrement();
				return true;
			}
		}
		return false;
	}

	/**
	 * Raises the level of the node up a single list level
	 * 
	 * @param prev
	 *            The expected previous item in the list
	 * @param down
	 *            The list item directly below this
	 * @param node
	 *            The node for the list items
	 */
	private Index<K, V> raiseSingleListLevel(Comparable<? super K> key,
			Index<K, V> prev, Index<K, V> down, Node<K, V> node) {
		// Allocate a new Item for this node
		Index<K, V> item = new Index<K, V>(node, down, null);
		if (prev == null)
			// Should not happen
			System.out.println("!!!!!!!!!!!!!!!!!!!!!!");
		// Loop until you find the previous Index item in the list
		for (;;) {
			Index<K, V> next = prev.right;
			int c = -1;
			if (next != null) {
				c = key.compareTo(next.node.key);
			}
			if (c < 0) {
				// Insert the new Item
				item.right = next;
				if (prev.casRight(next, item))
					return item;
			} else {
				prev = next;
			}
		}
	}

	/**
	 * Initialize the maintenance thread's index array. Fills the array with the
	 * index values from the beginning of the list
	 * 
	 * @param top
	 *            The index level to start at
	 * @param bottom
	 *            The index level to end at
	 */
	private void initializeIndexArray(HeadPointer<K, V> top,
			HeadPointer<K, V> bottom) {
		Index<K, V>[] array = thdLocalPrevArray.get();
		int topValue = top.value, bottomValue = bottom.value;
		Index<K, V> next = top.node;
		for (;;) {
			array[topValue] = next;
			if (topValue == bottomValue) {
				if (next != bottomStart.node)
					System.out.println("ERERERER!!!!!!!!!");
				break;
			}
			topValue--;
			next = next.down;
		}
	}

	/**
	 * After a modification, should an application thread try to perform
	 * maintenance
	 * 
	 * No longer used!
	 */
	private void checkMaint() {
		if (s_random.get().nextDouble() <= this.maintPercentage) {
			if (this.maintLock.tryLock()) {
				// this.doMaintenance(true);
				mixedTraversal();
				this.maintLock.unlock();
			}
		}
	}

	void finishCount(int nodesTraversed) {
		Vars vars = counts.get();
		vars.getCount++;
		vars.nodesTraversed += nodesTraversed;
	}

	/**
	 * Starts the maintenance thread
	 * 
	 * @return true
	 */
	public boolean startMaintenance() {
		if (this.seperateMaint) {
			stop = false;

			// If no mainteance thread, start a new one
			if (mainThd.get() == null) {
				if (mainThd.compareAndSet(null, new MaintenanceThread())) {
					mainThd.get().start();
				}
			}

		}
		return true;
	}

	/**
	 * Stops the maintenance thread, does not return until the thread actually
	 * stops
	 * 
	 * @return true
	 */
	public boolean stopMaintenance() {
		if (maintenance) {
			if (seperateMaint) {
				stop = true;
				MaintenanceThread thd = mainThd.get();
				if (thd != null) {
					try {
						// Wait for maintenance to stop
						thd.join();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					// Get rid of the mainteance thread
					mainThd.compareAndSet(thd, null);
				}
				// Remove this skip list from the ones that will be maintained
				skipLists.remove(this);
			}
			System.out
					.println("Height Changes: " + bottomLevelRaiseCount.get());
		}
		return true;
	}

	/**
	 * Loop performing maintenance on all the skip lists until stopped
	 */
	public static void doMaintenance() {
		while (!stop) {
			// Go through each skip list in the ones to be maintained,
			// performing a mixed traversal
			Object[] arry = (Object[]) skipLists.toArray();
			for (int i = 0; i < arry.length; i++) {
				((NonBlockingFriendlySkipListMap) arry[i]).mixedTraversal();
			}
		}
		if (STRUCT_MODS) {
			Object[] arry = (Object[]) skipLists.toArray();
			for (int i = 0; i < arry.length; i++) {
				((NonBlockingFriendlySkipListMap) arry[i]).vars.removals = counts
						.get().structMods;
			}
		}

	}

	@Override
	public void clear() {
		this.stopMaintenance();
		this.resetSkipList();
		this.startMaintenance();

		return;
	}

	/**
	 * Re-initialize the skip list and resets all counts
	 */
	public void resetSkipList() {
		initialize();
		this.bottomLevelRaiseCount.set(0);
		this.vars.heightChanges = 0;
		this.vars.removals = 0;
		this.vars.tallDeletedCount = 0;
		this.vars.totalCount = 0;
		this.vars.nonDeleted = 0;
	}

	public int getBottomLevelRaiseCount() {
		return this.bottomLevelRaiseCount.get();
	}

	/**
	 * Counts the total number of nodes in the skip list
	 * 
	 * @return the number of nodes
	 */
	public int numNodes() {
		int count = 0;
		Node<K, V> current = begin.next;

		while (current != null) {
			count++;
			current = current.next;
		}
		System.out.println();
		return count;
	}

	@Override
	public int size() {
		int count = 0;
		Node<K, V> current = begin.next;
		while (current != null) {
			if (current.value != null) {
				count++;
			}
			current = current.next;
		}
		return count;
	}

	public long getStructMods() {
		return vars.removals;
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		// TODO Auto-generated method stub
		return null;
	}

	/******************** Iterator stuff ********************/

	@Override
	public K firstKey() {
		Node<K, V> n = findFirst();
		if (n == null)
			throw new NoSuchElementException();
		return n.key;
	}

	@Override
	public boolean isEmpty() {
		return findFirst() == null;
	}

	Node<K, V> findFirst() {
		Node<K, V> n = begin;
		for (;;) {
			n = n.next;
			if (n == null)
				return null;
			if (n.value != null && n.value != n)
				return n;
		}
	}

	/**
	 * Base of iterator classes:
	 */
	abstract class Iter<T> implements Iterator<T> {
		/** the last node returned by next() */
		Node<K, V> lastReturned;
		/** the next node to return from next(); */
		Node<K, V> next;
		/** Cache of next value field to maintain weak consistency */
		V nextValue;

		/** Initializes ascending iterator for entire range. */
		Iter() {
			for (;;) {
				next = findFirst();
				if (next == null)
					break;
				Object x = next.value;
				if (x != null && x != next) {
					nextValue = (V) x;
					break;
				}
			}
		}

		public final boolean hasNext() {
			return next != null;
		}

		/** Advances next to higher entry. */
		final void advance() {
			if (next == null)
				throw new NoSuchElementException();
			lastReturned = next;
			for (;;) {
				next = next.next;
				if (next == null)
					break;
				Object x = next.value;
				if (x != null && x != next) {
					nextValue = (V) x;
					break;
				}
			}
		}

		public void remove() {
			Node<K, V> l = lastReturned;
			if (l == null)
				throw new IllegalStateException();
			// It would not be worth all of the overhead to directly
			// unlink from here. Using remove is fast enough.
			NonBlockingFriendlySkipListMap.this.remove(l.key);
			lastReturned = null;
		}

	}

	Iterator<V> valueIterator() {
		return new ValueIterator();
	}

	final class ValueIterator extends Iter<V> {
		public V next() {
			V v = nextValue;
			advance();
			return v;
		}
	}

	/** Lazily initialized values collection */
	private transient Values values;

	public Collection<V> values() {
		Values vs = values;
		return (vs != null) ? vs : (values = new Values(this));
	}

	static final class Values<E> extends AbstractCollection<E> {
		private final ConcurrentNavigableMap<Object, E> m;

		Values(ConcurrentNavigableMap<Object, E> map) {
			m = map;
		}

		public Iterator<E> iterator() {

			return ((NonBlockingFriendlySkipListMap<Object, E>) m)
					.valueIterator();

		}

		public boolean isEmpty() {
			return m.isEmpty();
		}

		public int size() {
			return m.size();
		}

		public boolean contains(Object o) {
			return m.containsValue(o);
		}

		public void clear() {
			m.clear();
		}

		public Object[] toArray() {
			// TODO Auto-generated method stub
			// return toList(this).toArray();
			return null;
		}

		public <T> T[] toArray(T[] a) {
			// TODO Auto-generated method stub
			// return toList(this).toArray(a);
			return null;
		}
	}

	@Override
	public boolean remove(Object arg0, Object arg1) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public V replace(K arg0, V arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean replace(K arg0, V arg1, V arg2) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public java.util.Map.Entry<K, V> ceilingEntry(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public K ceilingKey(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> firstEntry() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> floorEntry(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public K floorKey(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> higherEntry(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public K higherKey(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> lastEntry() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> lowerEntry(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public K lowerKey(K arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> pollFirstEntry() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public java.util.Map.Entry<K, V> pollLastEntry() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Comparator<? super K> comparator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public K lastKey() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NavigableSet<K> descendingKeySet() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> descendingMap() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> headMap(K toKey) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NavigableSet<K> keySet() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public NavigableSet<K> navigableKeySet() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> subMap(K fromKey,
			boolean fromInclusive, K toKey, boolean toInclusive) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
		// TODO Auto-generated method stub
		return null;
	}

}

package LockBasedFriendlyTreeMap;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The contention-friendly tree implementation of map 
 * as described in:
 *
 * T. Crain, V. Gramoli and M. Ryanla. 
 * A Contention-Friendly Binary Search Tree. 
 * Euro-Par 2013.
 * 
 * @author Tyler Crain
 * 
 * @param <K>
 * @param <V>
 */

public class LockBasedFriendlyTreeMap<K, V> extends AbstractMap<K, V> implements
		CompositionalMap<K, V>/*, MaintenanceAlg*/ {

	static final boolean useFairLocks = false;
	static final boolean allocateOutside = true;
	// we encode directions as characters
	static final char Left = 'L';
	static final char Right = 'R';
	final V DELETED = (V) new Object();

	/*private class MaintenanceThread extends Thread {
		LockBasedFriendlyTreeMap<K, V> map;

		MaintenanceThread(LockBasedFriendlyTreeMap<K, V> map) {
			this.map = map;
		}

		public void run() {
			map.doMaintenance();
		}
	}*/

	private class MaintVariables {
		long propogations = 0, rotations = 0;
	}

	private final MaintVariables vars = new MaintVariables();

	private static class Node<K, V> {
		K key;

		class BalanceVars {
			volatile int localh, lefth, righth;
		}

		final BalanceVars bal = new BalanceVars();
		volatile V value;
		volatile Node<K, V> left;
		volatile Node<K, V> right;
		final ReentrantLock lock;
		volatile boolean removed;

		Node(final K key, final V value) {
			this.key = key;
			this.value = value;
			this.removed = false;
			this.lock = new ReentrantLock(useFairLocks);
			this.right = null;
			this.left = null;
			this.bal.localh = 1;
			this.bal.righth = 0;
			this.bal.lefth = 0;
		}

		Node(final K key, final int localh, final int lefth, final int righth,
				final V value, final Node<K, V> left, final Node<K, V> right) {
			this.key = key;
			this.bal.localh = localh;
			this.bal.righth = righth;
			this.bal.lefth = lefth;
			this.value = value;
			this.left = left;
			this.right = right;
			this.lock = new ReentrantLock(useFairLocks);
			this.removed = false;
		}

		void setupNode(final K key, final int localh, final int lefth,
				final int righth, final V value, final Node<K, V> left,
				final Node<K, V> right) {
			this.key = key;
			this.bal.localh = localh;
			this.bal.righth = righth;
			this.bal.lefth = lefth;
			this.value = value;
			this.left = left;
			this.right = right;
			this.removed = false;
		}

		Node<K, V> child(char dir) {
			return dir == Left ? left : right;
		}

		Node<K, V> childSibling(char dir) {
			return dir == Left ? right : left;
		}

		void setChild(char dir, Node<K, V> node) {
			if (dir == Left) {
				left = node;
			} else {
				right = node;
			}
		}

		void updateLocalh() {
			this.bal.localh = Math.max(this.bal.lefth + 1, this.bal.righth + 1);
		}

	}

	// state
	private final Node<K, V> root = new Node<K, V>(null, null);
	private Comparator<? super K> comparator;
	volatile boolean stop = false;
	//private MaintenanceThread mainThd;
	// used in the getSize function
	int size;
	private long structMods = 0;

	// Constructors
	public LockBasedFriendlyTreeMap() {
		// temporary
		//this.startMaintenance();
	}

	public LockBasedFriendlyTreeMap(final Comparator<? super K> comparator) {
		// temporary
		//this.startMaintenance();
		this.comparator = comparator;
	}

	// What is this?
	private Comparable<? super K> comparable(final Object key) {
		if (key == null) {
			throw new NullPointerException();
		}
		if (comparator == null) {
			return (Comparable<? super K>) key;
		}
		return new Comparable<K>() {
			final Comparator<? super K> _cmp = comparator;

			@SuppressWarnings("unchecked")
			public int compareTo(final K rhs) {
				return _cmp.compare((K) key, rhs);
			}
		};
	}

	@Override
	public boolean containsKey(Object key) {
		if (get(key) == null) {
			return false;
		}
		return true;
	}

	public boolean contains(Object key) {
		if (get(key) == null) {
			return false;
		}
		return true;
	}

	void finishCount(int nodesTraversed) {
		Vars vars = counts.get();
		vars.getCount++;
		vars.nodesTraversed += nodesTraversed;
	}

	@Override
	public V get(final Object key) {
		Node<K, V> next, current;
		next = root;
		final Comparable<? super K> k = comparable(key);
		int rightCmp;

		int nodesTraversed = 0;

		while (true) {
			current = next;
			if (current.key == null) {
				rightCmp = -100;
			} else {
				rightCmp = k.compareTo(current.key);
			}
			if (rightCmp == 0) {
				V value = current.value;
				if (value == DELETED) {
					if (TRAVERSAL_COUNT) {
						finishCount(nodesTraversed);
					}
					return null;
				}
				if (TRAVERSAL_COUNT) {
					finishCount(nodesTraversed);
				}
				return value;
			}
			if (rightCmp <= 0) {
				next = current.left;
			} else {
				next = current.right;
			}
			if (TRAVERSAL_COUNT) {
				nodesTraversed++;
			}
			if (next == null) {
				if (TRAVERSAL_COUNT) {
					finishCount(nodesTraversed);
				}
				return null;
			}
		}
	}

	@Override
	public V remove(final Object key) {
		Node<K, V> next, current;
		next = root;
		final Comparable<? super K> k = comparable(key);
		int rightCmp;
		V value;

		while (true) {
			current = next;
			if (current.key == null) {
				rightCmp = -100;
			} else {
				rightCmp = k.compareTo(current.key);
			}
			if (rightCmp == 0) {
				if (current.value == DELETED) {
					return null;
				}
				current.lock.lock();
				if (!current.removed) {
					break;
				} else {
					current.lock.unlock();
				}
			}
			if (rightCmp <= 0) {
				next = current.left;
			} else {
				next = current.right;
			}
			if (next == null) {
				if (rightCmp != 0) {
					return null;
				}
				// this only happens if node is removed, so you take the
				// opposite path
				// this should never be null
				System.out.println("Going right");
				next = current.right;
			}
		}
		value = current.value;
		if (value == DELETED) {
			current.lock.unlock();
			return null;
		} else {
			current.value = DELETED;
			current.lock.unlock();
			// System.out.println("delete");
			return value;
		}
	}

	@Override
	public V putIfAbsent(K key, V value) {
		int rightCmp;
		Node<K, V> next, current;
		next = root;
		final Comparable<? super K> k = comparable(key);
		Node<K, V> n = null;
		// int traversed = 0;
		V val;

		while (true) {
			current = next;
			// traversed++;
			if (current.key == null) {
				rightCmp = -100;
			} else {
				rightCmp = k.compareTo(current.key);
			}
			if (rightCmp == 0) {
				val = current.value;
				if (val != DELETED) {
					// System.out.println(traversed);
					return val;
				}
				current.lock.lock();
				if (!current.removed) {
					break;
				} else {
					current.lock.unlock();
				}
			}
			if (rightCmp <= 0) {
				next = current.left;
			} else {
				next = current.right;
			}
			if (next == null) {
				if (n == null && allocateOutside) {
					n = new Node<K, V>(key, value);
				}
				current.lock.lock();
				if (!current.removed) {
					if (rightCmp <= 0) {
						next = current.left;
					} else {
						next = current.right;
					}
					if (next == null) {
						break;
					} else {
						current.lock.unlock();
					}
				} else {
					current.lock.unlock();
					// maybe have to check if the other one is still null before
					// going the opposite way?
					// YES!! We do this!
					if (rightCmp <= 0) {
						next = current.left;
					} else {
						next = current.right;
					}
					if (next == null) {
						if (rightCmp > 0) {
							next = current.left;
						} else {
							next = current.right;
						}
					}
				}
			}
		}
		val = current.value;
		if (rightCmp == 0) {
			if (val == DELETED) {
				current.value = value;
				current.lock.unlock();
				// System.out.println("insert");
				// System.out.println(traversed);
				return null;
			} else {
				current.lock.unlock();
				return val;
			}
		} else {
			if (!allocateOutside) {
				n = new Node<K, V>(key, value);
			}
			if (rightCmp <= 0) {
				current.left = n;
			} else {
				current.right = n;
			}
			current.lock.unlock();
			// System.out.println(traversed);
			// System.out.println("insert");
			return null;
		}
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		// TODO Auto-generated method stub
		return null;
	}

	// maintenance
	boolean removeNode(Node<K, V> parent, char direction) {
		Node<K, V> n, child;
		// can get before locks because only maintenance removes nodes
		if (parent.removed)
			return false;
		n = direction == Left ? parent.left : parent.right;
		if (n == null)
			return false;
		// get the locks
		n.lock.lock();
		parent.lock.lock();
		if (n.value != DELETED) {
			n.lock.unlock();
			parent.lock.unlock();
			return false;
		}
		if ((child = n.left) != null) {
			if (n.right != null) {
				n.lock.unlock();
				parent.lock.unlock();
				return false;
			}
		} else {
			child = n.right;
		}
		if (direction == Left) {
			parent.left = child;
		} else {
			parent.right = child;
		}
		n.left = parent;
		n.right = parent;
		n.removed = true;
		n.lock.unlock();
		parent.lock.unlock();
		// System.out.println("removed a node");
		// need to update balance values here
		if (direction == Left) {
			parent.bal.lefth = n.bal.localh - 1;
		} else {
			parent.bal.righth = n.bal.localh - 1;
		}
		parent.updateLocalh();
		return true;
	}

	int rightRotate(Node<K, V> parent, char direction, boolean doRotate) {
		Node<K, V> n, l, lr, r, newNode;
		if (parent.removed)
			return 0;
		n = direction == Left ? parent.left : parent.right;
		if (n == null)
			return 0;
		l = n.left;
		if (l == null)
			return 0;
		if (l.bal.lefth - l.bal.righth < 0 && !doRotate) {
			// should do a double rotate
			return 2;
		}
		if (allocateOutside) {
			newNode = new Node<K, V>(null, null);
		}
		parent.lock.lock();
		n.lock.lock();
		l.lock.lock();
		lr = l.right;
		r = n.right;
		if (allocateOutside) {
			newNode.setupNode(n.key,
					Math.max(1 + l.bal.righth, 1 + n.bal.righth), l.bal.righth,
					n.bal.righth, n.value, lr, r);
		} else {
			newNode = new Node<K, V>(n.key, Math.max(1 + l.bal.righth,
					1 + n.bal.righth), l.bal.righth, n.bal.righth, n.value, lr,
					r);
		}
		l.right = newNode;
		n.removed = true;
		if (direction == Left) {
			parent.left = l;
		} else {
			parent.right = l;
		}
		l.lock.unlock();
		n.lock.unlock();
		parent.lock.unlock();
		// need to update balance values
		l.bal.righth = newNode.bal.localh;
		l.updateLocalh();
		if (direction == Left) {
			parent.bal.lefth = l.bal.localh;
		} else {
			parent.bal.righth = l.bal.localh;
		}
		parent.updateLocalh();
		if (STRUCT_MODS) {
			vars.rotations++;
			counts.get().structMods++;
		}
		// System.out.println("right rotate");
		return 1;
	}

	int leftRotate(Node<K, V> parent, char direction, boolean doRotate) {
		Node<K, V> n, r, rl, l, newNode;
		if (parent.removed)
			return 0;
		n = direction == Left ? parent.left : parent.right;
		if (n == null)
			return 0;
		r = n.right;
		if (r == null)
			return 0;
		if (r.bal.lefth - r.bal.righth > 0 && !doRotate) {
			// should do a double rotate
			return 3;
		}
		if (allocateOutside) {
			newNode = new Node<K, V>(null, null);
		}
		parent.lock.lock();
		n.lock.lock();
		r.lock.lock();
		rl = r.left;
		l = n.left;
		if (allocateOutside) {
			newNode.setupNode(n.key,
					Math.max(1 + r.bal.lefth, 1 + n.bal.lefth), n.bal.lefth,
					r.bal.lefth, n.value, l, rl);
		} else {
			newNode = new Node<K, V>(n.key, Math.max(1 + r.bal.lefth,
					1 + n.bal.lefth), n.bal.lefth, r.bal.lefth, n.value, l, rl);
		}
		r.left = newNode;

		// temp (Need to fix this!!!!!!!!!!!!!!!!!!!!)
		n.right = parent;
		n.left = parent;

		n.removed = true;
		if (direction == Left) {
			parent.left = r;
		} else {
			parent.right = r;
		}
		r.lock.unlock();
		n.lock.unlock();
		parent.lock.unlock();
		// need to update balance values
		r.bal.righth = newNode.bal.localh;
		r.updateLocalh();
		if (direction == Left) {
			parent.bal.lefth = r.bal.localh;
		} else {
			parent.bal.righth = r.bal.localh;
		}
		parent.updateLocalh();
		if (STRUCT_MODS) {
			vars.rotations++;
			counts.get().structMods++;
		}
		// System.out.println("left rotate");
		return 1;
	}

	boolean propagate(Node<K, V> node) {
		Node<K, V> lchild, rchild;

		lchild = node.left;
		rchild = node.right;

		if (lchild == null) {
			node.bal.lefth = 0;
		} else {
			node.bal.lefth = lchild.bal.localh;
		}
		if (rchild == null) {
			node.bal.righth = 0;
		} else {
			node.bal.righth = rchild.bal.localh;
		}

		node.updateLocalh();
		if (STRUCT_MODS)
			vars.propogations++;

		if (Math.abs(node.bal.righth - node.bal.lefth) >= 2)
			return true;
		return false;
	}

	boolean performRotation(Node<K, V> parent, char direction) {
		int ret;
		Node<K, V> node;

		ret = singleRotation(parent, direction, false, false);
		if (ret == 2) {
			// Do a LRR
			node = direction == Left ? parent.left : parent.right;
			ret = singleRotation(node, Left, true, false);
			if (ret > 0) {
				if (singleRotation(parent, direction, false, true) > 0) {
					// System.out.println("LRR");
				}
			}
		} else if (ret == 3) {
			// Do a RLR
			node = direction == Left ? parent.left : parent.right;
			ret = singleRotation(node, Right, false, true);
			if (ret > 0) {
				if (singleRotation(parent, direction, true, false) > 0) {
					// System.out.println("RLR");
				}
			}
		}
		if (ret > 0)
			return true;
		return false;
	}

	int singleRotation(Node<K, V> parent, char direction, boolean leftRotation,
			boolean rightRotation) {
		int bal, ret = 0;
		Node<K, V> node, child;

		node = direction == Left ? parent.left : parent.right;
		bal = node.bal.lefth - node.bal.righth;
		if (bal >= 2 || rightRotation) {
			// check reiable and rotate
			child = node.left;
			if (child != null) {
				if (node.bal.lefth == child.bal.localh) {
					ret = rightRotate(parent, direction, rightRotation);
				}
			}
		} else if (bal <= -2 || leftRotation) {
			// check reliable and rotate
			child = node.right;
			if (child != null) {
				if (node.bal.righth == child.bal.localh) {
					ret = leftRotate(parent, direction, leftRotation);
				}
			}
		}
		return ret;
	}

	boolean recursivePropagate(Node<K, V> parent, Node<K, V> node,
			char direction) {
		Node<K, V> left, right;

		if (node == null)
			return true;
		left = node.left;
		right = node.right;

		if (!node.removed && node.value == DELETED
				&& (left == null || right == null) && node != this.root) {
			if (removeNode(parent, direction)) {
				return true;
			}
		}

		if (stop) {
			return true;
		}

		if (!node.removed) {
			if (left != null) {
				recursivePropagate(node, left, Left);
			}
			if (right != null) {
				recursivePropagate(node, right, Right);
			}
		}

		if (stop) {
			return true;
		}

		// no rotations for now
		if (!node.removed && node != this.root) {
			if (propagate(node)) {
				this.performRotation(parent, direction);
			}
		}

		return true;
	}

	/*public boolean stopMaintenance() {
		this.stop = true;
		try {
			this.mainThd.join();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}*/

	/*public boolean startMaintenance() {
		this.stop = false;

		mainThd = new MaintenanceThread(this);

		mainThd.start();

		return true;
	}*/

	boolean doMaintenance() {
		while (!stop) {
			recursivePropagate(this.root, this.root.left, Left);
		}
		if (STRUCT_MODS)
			this.structMods += counts.get().structMods;
		System.out.println("Propogations: " + vars.propogations);
		System.out.println("Rotations: " + vars.rotations);
		return true;
	}

	// not thread safe
	public int getSize() {
		this.size = 0;
		recursiveGetSize(root.left);
		return size;
	}

	void recursiveGetSize(Node<K, V> node) {
		if (node == null)
			return;
		if (node.removed) {
			// System.out.println("Shouldn't find removed nodes in the get size function");
		}
		if (node.value != DELETED) {
			this.size++;
		}
		recursiveGetSize(node.left);
		recursiveGetSize(node.right);
	}

	public int numNodes() {
		this.size = 0;
		ConcurrentHashMap<Integer, Node<K, V>> map = new ConcurrentHashMap<Integer, Node<K, V>>();
		recursiveNumNodes(root.left, map);
		return size;
	}

	void recursiveNumNodes(Node<K, V> node,
			ConcurrentHashMap<Integer, Node<K, V>> map) {
		if (node == null)
			return;
		if (node.removed) {
			// System.out.println("Shouldn't find removed nodes in the get size function");
		}
		Node<K, V> n = map.putIfAbsent((Integer) node.key, node);
		if (n != null) {
			System.out.println("Error: " + node.key);
		}
		this.size++;
		recursiveNumNodes(node.left, map);
		recursiveNumNodes(node.right, map);
	}

	public int getBalance() {
		int lefth = 0, righth = 0;
		if (root.left == null)
			return 0;
		lefth = recursiveDepth(root.left.left);
		righth = recursiveDepth(root.left.right);
		return lefth - righth;
	}

	int recursiveDepth(Node<K, V> node) {
		if (node == null) {
			return 0;
		}
		int lefth, righth;
		lefth = recursiveDepth(node.left);
		righth = recursiveDepth(node.right);
		return Math.max(lefth, righth) + 1;
	}

	@Override
	public void clear() {
		//this.stopMaintenance();
		this.resetTree();
		//this.startMaintenance();

		return;
	}

	private void resetTree() {
		this.structMods = 0;
		this.vars.propogations = 0;
		this.vars.rotations = 0;
		root.left = null;
	}

	@Override
	public int size() {
		return this.getSize();
	}

	public long getStructMods() {
		return structMods;
	}

}

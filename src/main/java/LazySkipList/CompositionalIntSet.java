package LazySkipList;

import java.util.Collection;

/*
 * Compositional integer set interface
 * 
 * @author Vincent Gramoli
 *
 */
public interface CompositionalIntSet {
	
	public void fill(int range, long size);
	
	public boolean addInt(int x);
	public boolean removeInt(int x);
	public boolean containsInt(int x);
	public Object getInt(int x);

	public boolean addAll(Collection<Integer> c);
	public boolean removeAll(Collection<Integer> c);
	
	public int size();
	
	public void clear();
	
	public String toString();
	
	public Object putIfAbsent(int x, int y);
	
	
}

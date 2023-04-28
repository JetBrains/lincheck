package LogicalOrderingAVL;

import java.util.Map;

/*
 * Compositional map interface
 * 
 * @author Vincent Gramoli
 *
 */
public interface CompositionalMap<K, V> extends Map<K, V> {
	
    public static final boolean TRAVERSAL_COUNT = false;
    public static final boolean STRUCT_MODS = false;
    
    public class Vars {
    	public long iteration = 0;
    	public long getCount = 0;
    	public long nodesTraversed = 0;
    	public long structMods = 0;
    }
    
    public final static ThreadLocal<Vars> counts = new ThreadLocal<Vars>() {
        @Override
        protected synchronized Vars initialValue() {
            return new Vars();
        }
    };
	
	public V putIfAbsent(K k, V v);

	public void clear();
	
	public int size();
}


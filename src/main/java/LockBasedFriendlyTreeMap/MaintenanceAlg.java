package LockBasedFriendlyTreeMap;

/*
 * Compositional map interface
 * 
 * @author Vincent Gramoli
 *
 */
public interface MaintenanceAlg {

	/**
	 * If set to true then a lock-free version of the algorithm is used
	 */
	static final boolean lockFree = true;
	
	public boolean stopMaintenance();
	public long getStructMods();
	public int numNodes();
}

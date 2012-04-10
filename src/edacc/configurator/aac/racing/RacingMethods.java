package edacc.configurator.aac.racing;

import java.util.List;
import java.util.Random;

import edacc.api.API;
import edacc.configurator.aac.AAC;
import edacc.configurator.aac.Parameters;
import edacc.configurator.aac.SolverConfiguration;

public abstract class RacingMethods {	
	API api;
	AAC pacc;
	Parameters parameters;
	protected Random rng;
	protected int numCompCalls;
	SolverConfiguration firstSC;
	
	public RacingMethods(AAC pacc, Random rng, API api, Parameters parameters, SolverConfiguration firstSC) {
		this.pacc = pacc;
		this.api = api;
		this.parameters = parameters;
		this.rng = rng;
		this.numCompCalls = 0;
		this.firstSC = firstSC;
	}
	
	/**
	 * Compares solver configuration <code>sc1</code> to solver configuration <code>sc2</code> using the statistics </br>
	 * of this racing method. </br>
	 * </br>
	 * Returns -1 if <code>sc2</code> is better than <code>sc1</code>.</br>
	 * Returns 0 if there aren't any significant differences between the solver configurations.</br>
	 * Returns 1 if <code>sc1</code> is better than <code>sc2</code>.
	 * @param sc1
	 * @param sc2
	 * @return
	 */
	public abstract int compareTo(SolverConfiguration sc1, SolverConfiguration sc2);
	
	/**
	 * Returns the <code>numSC</code> best solver configurations found so far.</br>
	 * Returns an empty list if there aren't any.
	 * @param numSC
	 * @return
	 */
	public abstract List<SolverConfiguration> getBestSolverConfigurations(Integer numSC);
	
	/**
	 * Called as soon as some solver configurations have completed their runs.
	 * @param scs
	 * @throws Exception
	 */
	public abstract void solverConfigurationsFinished(List<SolverConfiguration> scs) throws Exception;
	
	/**
	 * Called as soon as some solver configurations were created and should be raced.
	 * @param scs
	 * @throws Exception
	 */
	public abstract void solverConfigurationsCreated(List<SolverConfiguration> scs) throws Exception;
	/**
	 * Determines how many new solver configuration can be taken into
	 * consideration.
	 * 
	 * @throws Exception
	 */
	public abstract int computeOptimalExpansion(int computationCoreCount, int computationJobCount, int listNewSCSize);
	
	public abstract void listParameters();
	public int getNumCompCalls(){
		return this.numCompCalls;
	}
}

package edacc.configurator.aac.search;

import java.util.List;
import java.util.Random;

import edacc.api.API;
import edacc.configurator.aac.AAC;
import edacc.configurator.aac.Parameters;
import edacc.configurator.aac.SolverConfiguration;

public abstract class SearchMethods {
	protected AAC pacc;
	protected API api;
	protected Random rng;
	protected Parameters parameters;
	protected SolverConfiguration firstSC;
	
	public SearchMethods(AAC pacc, API api, Random rng, Parameters parameters, SolverConfiguration firstSC) {
		this.api = api;
		this.parameters = parameters;
		this.rng = rng;
		this.pacc = pacc;
		this.firstSC = firstSC;
	}
	
	/**
	 * Generates num new solver configurations
	 * 
	 * @return a List of the new solver configurations
	 * @throws Exception
	 */
	public abstract List<SolverConfiguration> generateNewSC(int num) throws Exception;
	
	public abstract void listParameters();
}

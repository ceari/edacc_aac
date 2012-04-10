package edacc.configurator.aac.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edacc.api.API;
import edacc.configurator.aac.AAC;
import edacc.configurator.aac.Parameters;
import edacc.configurator.aac.SolverConfiguration;
import edacc.parameterspace.ParameterConfiguration;
import edacc.parameterspace.graph.ParameterGraph;

public class ROAR extends SearchMethods {

	private ParameterGraph graph;

	public ROAR(AAC pacc, API api, Random rng, Parameters parameters, SolverConfiguration firstSC) throws Exception {
		super(pacc, api, rng, parameters, firstSC);
		graph = api.loadParameterGraphFromDB(parameters.getIdExperiment());
	}

	@Override
	public List<SolverConfiguration> generateNewSC(int num) throws Exception {
		List<SolverConfiguration> res = new ArrayList<SolverConfiguration>();
		for (int i = 0; i < num; i++) {
			ParameterConfiguration paramconfig = graph.getRandomConfiguration(rng);
			int idSolverConfig = api.createSolverConfig(parameters.getIdExperiment(), paramconfig, api.getCanonicalName(parameters.getIdExperiment(), paramconfig));
			res.add(new SolverConfiguration(idSolverConfig, paramconfig, parameters.getStatistics()));
		}
		return res;
	}

	@Override
	public void listParameters() {
		// TODO Auto-generated method stub
		
	}

}

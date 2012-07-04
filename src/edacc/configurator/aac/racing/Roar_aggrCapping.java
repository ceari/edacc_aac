package edacc.configurator.aac.racing;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import edacc.api.API;
import edacc.api.costfunctions.CostFunction;
import edacc.configurator.aac.AAC;
import edacc.configurator.aac.InstanceIdSeed;
import edacc.configurator.aac.Parameters;
import edacc.configurator.aac.SolverConfiguration;
import edacc.configurator.aac.clustering.CLC_Clustering;
import edacc.configurator.aac.clustering.ClusterMethods;
import edacc.model.ConfigurationScenarioDAO;
import edacc.model.ExperimentResult;
import edacc.parameterspace.ParameterConfiguration;
import edacc.parameterspace.graph.ParameterGraph;

public class Roar_aggrCapping extends RacingMethods {
	SolverConfiguration bestSC;
	int incumbentNumber;
	int num_instances;
	HashSet<Integer> stopEvalSolverConfigIds = new HashSet<Integer>();
	
	int numberOfMinStartupSCs = 20;
	boolean clustering = false;
	float maxCappingFactor = 2f;
	ParameterGraph paramGraph;
	ClusterMethods clusterHandler;
	
	public Roar_aggrCapping(AAC proar, Random rng, API api, Parameters parameters, List<SolverConfiguration> firstSCs, List<SolverConfiguration> referenceSCs) throws Exception {
		super(proar, rng, api, parameters, firstSCs, referenceSCs);
		incumbentNumber = 0;
		num_instances = ConfigurationScenarioDAO.getConfigurationScenarioByExperimentId(parameters.getIdExperiment()).getCourse().getInitialLength();
		
		if(clustering) {
			initClustering();
		} else {
			initBestSC(firstSCs.get(0));
		}
	}
	
	
	private void initClustering() throws Exception {
		// Gathers a list of SCs to initialize the Clusters and of course the incumbent
		List<SolverConfiguration> startupSCs = new ArrayList<SolverConfiguration>();
		pacc.log("c Initialize clustering with the following SCs ...");
		
		// All reference SCs are added
		if(referenceSCs.size() > 0) {
			pacc.log("c Reference SCs:");
			for (SolverConfiguration refSc : referenceSCs) {
				pacc.log("c "+startupSCs.size()+": "+refSc.getName());
				startupSCs.add(refSc);
			}
		}
		
		// Default SCs are added. The maximum is the given number of startup SCs
		int defaultSCs = Math.min(firstSCs.size(), numberOfMinStartupSCs);
		pacc.log("c Default SCs:");
		for (int i = 0; i < defaultSCs; i++) {
			pacc.log("c "+startupSCs.size()+": "+firstSCs.get(i).getName());
			startupSCs.add(firstSCs.get(i));
		}
		
		// At least (number of minimal startupSCs)/2 random SCs are added. Improves the reliability of the predefined data. 
		pacc.log("c Random SCs:");
		for (int i = 0; i < (int)(numberOfMinStartupSCs/2); i++) {
			ParameterConfiguration randomConf = paramGraph.getRandomConfiguration(rng);
			try {
				int scID = api.createSolverConfig(parameters.getIdExperiment(), randomConf, 
						api.getCanonicalName(parameters.getIdExperiment(), randomConf));
				SolverConfiguration randomSC = new SolverConfiguration(scID, randomConf, parameters.getStatistics());
				startupSCs.add(randomSC);
				pacc.log("c "+startupSCs.size()+": "+randomSC.getName());
			} catch (Exception e) {
				pacc.log("w A new random configuration could not be created for the initialising of the clustering!");
				e.printStackTrace();
			}
		}
		
		
		// Run the configs on the whole parcour length
		pacc.log("c Adding jobs for the initial SCs...");
		for (SolverConfiguration sc : startupSCs) {
			int expansion = 0;
			if (sc.getJobCount() < parameters.getMaxParcoursExpansionFactor() * num_instances) {
				expansion = parameters.getMaxParcoursExpansionFactor() * num_instances - sc.getJobCount();
				pacc.expandParcoursSC(sc, expansion);
			}
			if (expansion > 0) {
				pacc.log("c Expanding parcours of SC " + sc.getIdSolverConfiguration() + " by " + expansion);
			}
		}
		
		// Wait for the configs to finish
		boolean finished = false;
		while(!finished) {
			finished = true;
			pacc.log("c Waiting for initial Scs to finish their jobs");
			for (SolverConfiguration sc : startupSCs) {	
				pacc.updateJobsStatus(sc);
				if (!(sc.getNotStartedJobs().isEmpty() && sc.getRunningJobs().isEmpty())) {
						finished = false;
				}
				pacc.sleep(1000);
			} 
		}
	
	 	// Set bestSc
		float bestCost = Float.MAX_VALUE;
		for (SolverConfiguration sc : startupSCs) {
			if(sc.getCost() < bestCost) {
				bestCost = sc.getCost();
				bestSC = sc;
			}
		}
		
		// Initialize Clustering
		clusterHandler = new CLC_Clustering(parameters, api, rng, startupSCs);
		
	}
	
	private void initBestSC(SolverConfiguration sc) throws Exception {
		this.bestSC = firstSCs.get(0);
		bestSC.setIncumbentNumber(incumbentNumber++);
		pacc.log("i " + pacc.getWallTime() + " ," + bestSC.getCost() + ", n.A.," + bestSC.getIdSolverConfiguration() + ", n.A.," + bestSC.getParameterConfiguration().toString());
		
		int expansion = 0;
		if (bestSC.getJobCount() < parameters.getMaxParcoursExpansionFactor() * num_instances) {
			expansion = Math.min(parameters.getMaxParcoursExpansionFactor() * num_instances - bestSC.getJobCount(), parameters.getInitialDefaultParcoursLength());
			pacc.expandParcoursSC(bestSC, expansion);
		}
		if (expansion > 0) {
			pacc.log("c Expanding parcours of best solver config " + bestSC.getIdSolverConfiguration() + " by " + expansion);
		}
		// update the status of the jobs of bestSC and if first level wait
		// also for jobs to finish
		if (expansion > 0) {
			pacc.log("c Waiting for currently best solver config " + bestSC.getIdSolverConfiguration() + " to finish " + expansion + "job(s)");
			while (true) {
				pacc.updateJobsStatus(bestSC);
				if (bestSC.getNotStartedJobs().isEmpty() && bestSC.getRunningJobs().isEmpty()) {
					break;
				}
				pacc.sleep(1000);
			}
		} else {
			pacc.updateJobsStatus(bestSC);
		}
	}
	

	public String toString(){
		return "Roar with aggressive capping.";
	}
	
	@Override
	public int compareTo(SolverConfiguration sc1, SolverConfiguration sc2) {
		return sc1.compareTo(sc2);
	}

	@Override
	public void solverConfigurationsFinished(List<SolverConfiguration> scs) throws Exception {
		aggressiveCapping(pacc.returnListNewSC());
		
		if(clustering) {
			boolean runBest = false;
			for (SolverConfiguration sc : scs) {
				boolean equalRuns = true;
				if (sc == bestSC) continue;
				int[] competitor = clusterHandler.countRunPerCluster(sc);
				int[] best = clusterHandler.countRunPerCluster(sc);
				for (int i = 0; i < best.length; i++) {
					// incumbent always has most runs
					if(best[i] < competitor[i]) {
						InstanceIdSeed inst = clusterHandler.getInstanceInCluster(i);
						pacc.addJob(bestSC, inst.seed, inst.instanceId, bestSC.getIncumbentNumber());					
						pacc.log("c Generated " +(competitor[i]-best[i]) + " jobs for the bestSC for cluster " + i);
						runBest = true;
					} else if(best[i] > competitor[i]) {
						equalRuns = false;
					}
				}
				if(equalRuns) {
					int comp = compareTo(sc, bestSC);
					if (comp > 0) {
						bestSC = sc;
						clusterHandler.addDataForClustering(sc);
						sc.setIncumbentNumber(incumbentNumber++);
						pacc.log("i " + pacc.getWallTime() + "," + sc.getCost() + ",n.A. ," + sc.getIdSolverConfiguration() + ",n.A. ," + sc.getParameterConfiguration().toString());
					}
				} else {
					int runsToAdd = sc.getJobCount();
					int bestSCRuns = 0;
					if(runsToAdd*2 > bestSC.getJobCount()) {
						bestSCRuns = runsToAdd*2 - bestSC.getJobCount();
						runsToAdd = bestSC.getJobCount() - runsToAdd;
					}
					while(runsToAdd > 0) {
						// ToDo: 0 (incl) - max (exclusive)?
						int rand = rng.nextInt(competitor.length);
						if(competitor[rand] < best[rand]) {
							int diff = best[rand] - competitor[rand];
							for (int i = 0; i < diff; i++) {
								InstanceIdSeed newRun = clusterHandler.getInstanceInCluster(rand);
								pacc.addJob(sc, newRun.seed, newRun.instanceId, sc.getIncumbentNumber());
								runsToAdd--;
							}
						}
					}
					for (int i = 0; i < bestSCRuns; i++) {
						int runsAdded = 0;
						while(runsAdded < bestSCRuns) {
							int rand = rng.nextInt(competitor.length);
							if(null != clusterHandler.getInstanceInCluster(rand)) {
								InstanceIdSeed newRun = clusterHandler.getInstanceInCluster(rand);
								pacc.addJob(sc, newRun.seed, newRun.instanceId, sc.getIncumbentNumber());
								pacc.addJob(bestSC, newRun.seed, newRun.instanceId, bestSC.getIncumbentNumber());
								pacc.addSolverConfigurationToListNewSC(sc);
								runBest = true;	
							}
						}
					}
				}
			}
			if(runBest) pacc.addSolverConfigurationToListNewSC(bestSC);
			return;
		}
		
		for (SolverConfiguration sc : scs) {
			if (sc == bestSC) 
				continue;
			int comp = compareTo(sc, bestSC);
			if (!stopEvalSolverConfigIds.contains(sc.getIdSolverConfiguration()) && comp >= 0) {
				if (sc.getJobCount() == bestSC.getJobCount()) {
					sc.setFinished(true);
					// all jobs from bestSC computed and won against
					// best:
					if (comp > 0) {
						bestSC = sc;
						sc.setIncumbentNumber(incumbentNumber++);
						pacc.log("i " + pacc.getWallTime() + "," + sc.getCost() + ",n.A. ," + sc.getIdSolverConfiguration() + ",n.A. ," + sc.getParameterConfiguration().toString());
					}
					// api.updateSolverConfigurationCost(sc.getIdSolverConfiguration(),
					// sc.getCost(),
					// statistics.getCostFunction());
					// listNewSC.remove(i);
				} else {
					int generated = pacc.addRandomJob(sc.getJobCount(), sc, bestSC, Integer.MAX_VALUE - sc.getNumber());
					pacc.log("c Generated " + generated + " jobs for solver config id " + sc.getIdSolverConfiguration());
					pacc.addSolverConfigurationToListNewSC(sc);
				}
			} else {// lost against best on part of the actual (or should not be evaluated anymore)
					// parcours:
				stopEvalSolverConfigIds.remove(sc.getIdSolverConfiguration());
				
				sc.setFinished(true);
				if (parameters.isDeleteSolverConfigs())
					api.removeSolverConfig(sc.getIdSolverConfiguration());
				pacc.log("d Solver config " + sc.getIdSolverConfiguration() + " with cost " + sc.getCost() + " lost against best solver config on " + sc.getJobCount() + " runs.");
				if (bestSC.getJobCount() < parameters.getMaxParcoursExpansionFactor() * num_instances) {
					pacc.log("c Expanding parcours of best solver config " + bestSC.getIdSolverConfiguration() + " by 1");
					pacc.expandParcoursSC(bestSC, 1);
					pacc.addSolverConfigurationToListNewSC(bestSC);
				}
			}
		}
	}

	@Override
	public void solverConfigurationsCreated(List<SolverConfiguration> scs) throws Exception {
		if (scs.isEmpty())
			return;
		
		if (bestSC == null) {
			initBestSC(scs.get(0));
			scs.remove(0);
		}
		
		for (SolverConfiguration sc : scs) {
			// add 1 random job from the best configuration with the
			// priority corresponding to the level
			// lower levels -> higher priorities
			pacc.addRandomJob(parameters.getMinRuns(), sc, bestSC, Integer.MAX_VALUE - sc.getNumber());
			pacc.addSolverConfigurationToListNewSC(sc);
		}
	}

	@Override
	public int computeOptimalExpansion(int coreCount, int jobs, int listNewSCSize) {
		int res = 0;
		if (coreCount < parameters.getMinCPUCount() || coreCount > parameters.getMaxCPUCount()) {
			pacc.log("w Warning: Current core count is " + coreCount);
		}
		int staticVariance = 30;
		if(parameters.getJobCPUTimeLimit() < 4) staticVariance = 10;
		double preferredWorkloadFactor = Math.pow(2, (staticVariance + parameters.getJobCPUTimeLimit()) / parameters.getJobCPUTimeLimit());
		preferredWorkloadFactor = Math.min(10, preferredWorkloadFactor);
		preferredWorkloadFactor = Math.max(2, preferredWorkloadFactor);
		
		if(jobs < preferredWorkloadFactor*coreCount) {
			res = (int) ((preferredWorkloadFactor*coreCount) - jobs) / parameters.getMinRuns();
		}
		if (listNewSCSize == 0 && res == 0) {
			res = 1;
		}
		return res;
	}
	
	/**
	 * every time the <code>solverConfigurationsFinished(List<SolverConfiguration> scs)</code>-method is called all 
	 * solver configurations with waiting or running jobs are analysed. If a solver configuration is with less runs 
	 * way worse than the incumbent it is capped.
	 * 
	 * @param listNewSC a list of all solver configurations with running or waiting jobs
	 */
	private void aggressiveCapping(HashMap<Integer, SolverConfiguration> listNewSC) {
		if((parameters.getStatistics().getCostFunction() instanceof edacc.api.costfunctions.PARX) ||
				(parameters.getStatistics().getCostFunction() instanceof edacc.api.costfunctions.Average)) {
			Collection<SolverConfiguration> scs = listNewSC.values();
			Iterator<SolverConfiguration> scsIter = scs.iterator();
			while(scsIter.hasNext()) {
				SolverConfiguration sc = scsIter.next();
				if(sc.getFinishedJobs().size() < parameters.getMinRuns()) continue;
				Point costs = getCostsOfTheSolverConfigs(bestSC, sc);
				double cappingFactor = Math.pow(maxCappingFactor, (1-(sc.getJobCount()/bestSC.getJobCount())));
				if(cappingFactor < 1) cappingFactor = 1.d;
				if (costs.getY() > cappingFactor*costs.getX()) {
					pacc.log("c COST Competitor (" + costs.getY() + ") > Incumbent ("+ (float) cappingFactor +"*" + costs.getX()+")"); 
					pacc.log("c RUNS Competitor (" + sc.getJobCount() + ") < Incumbent (" + bestSC.getJobCount()+")");
					List<ExperimentResult> jobsToKill = sc.getJobs(); 
					for (ExperimentResult j : jobsToKill) {
						try {
							api.killJob(j.getId());
						} catch (Exception e) {
							pacc.log("w Warning: Job "+j.getId()+" could not be killed!");
						} 
					}
					try {
						api.removeSolverConfig(sc.getIdSolverConfiguration());
					} catch (Exception e) {
						pacc.log("w Warning: SolverConfiguration "+sc.getIdSolverConfiguration()+" could not be removed!");
					}
					scsIter.remove();	
					pacc.log("c SolverConfiguration "+sc.getIdSolverConfiguration()+" was capped!"); 
				}
			}
		}
	}
	
	/**
	 * calculates the costs of the incumbent and a competitor on the same experiment results
	 * 
	 * @param best the incumbent ( bestSC )
	 * @param other solver configuration which is raced against the incumbent
	 * @return
	 */
	private Point getCostsOfTheSolverConfigs(SolverConfiguration best, SolverConfiguration other) {
		HashMap<InstanceIdSeed, ExperimentResult> bestJobs = new HashMap<InstanceIdSeed, ExperimentResult>();
		for (ExperimentResult job : best.getFinishedJobs()) {
			bestJobs.put(new InstanceIdSeed(job.getInstanceId(), job.getSeed()), job);
		}
		List<ExperimentResult> bestJobsInCommon = new LinkedList<ExperimentResult>();
		List<ExperimentResult> otherJobsInCommon = new LinkedList<ExperimentResult>();
		for (ExperimentResult job : other.getFinishedJobs()) {
			InstanceIdSeed tmp = new InstanceIdSeed(job.getInstanceId(), job.getSeed());
			ExperimentResult bestJob;
			if ((bestJob = bestJobs.get(tmp)) != null) {
				bestJobsInCommon.add(bestJob);
				otherJobsInCommon.add(job);
			}
		}
		Point costs = new Point();
		CostFunction costFunc = parameters.getStatistics().getCostFunction();
		float costBest = costFunc.calculateCost(bestJobsInCommon);
		float costOther = costFunc.calculateCost(otherJobsInCommon);
		costs.setLocation(costBest, costOther);
		return costs;
	}
	
	@Override
	public List<String> getParameters() {
		List<String> p = new LinkedList<String>();
		return p;
	}

	@Override
	public List<SolverConfiguration> getBestSolverConfigurations() {
		List<SolverConfiguration> res = new LinkedList<SolverConfiguration>();
		if (bestSC != null) {
			res.add(bestSC);
		}
		return res;
	}

	@Override
	public void stopEvaluation(List<SolverConfiguration> scs) throws Exception {
		for (SolverConfiguration sc : scs) {
			stopEvalSolverConfigIds.add(sc.getIdSolverConfiguration());
		}
	}

	@Override
	public void raceFinished() {
		try {
			pacc.updateSolverConfigName(bestSC, true);
		} catch (Exception e) {
			pacc.log("Error: Incumbent name could not be changed!");
			e.printStackTrace();
		}
	}

}

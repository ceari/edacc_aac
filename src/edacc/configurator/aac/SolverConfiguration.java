package edacc.configurator.aac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import edacc.parameterspace.ParameterConfiguration;
import edacc.api.API;
import edacc.model.ExperimentResult;
import edacc.model.ResultCode;
import edacc.model.StatusCode;

public class SolverConfiguration implements Comparable<SolverConfiguration> {
	/** the parameter configuration of the solver configuration */
	private ParameterConfiguration pConfig;

	/** id of the solver configuration from the DB */
	private int idSolverConfiguration;

	private int incumbentNumber;
	/**
	 * the cost of the configuration with regards to a statistic function and a
	 * metric (cost or runtime);
	 */
	private Double cost;
	
	private Double dbCost;

	public Double getDbCost() {
		return dbCost;
	}

	/**
	 * total runtime in seconds of all the jobs in the jobs list
	 * 
	 */
	private float totalRuntime;

	/** the name of the configuration */
	private String nameRacing, nameSearch;
	protected boolean nameUpdated, wasBest;

	/** List of all jobs that a solver configuration has been executed so far */
	private List<ExperimentResult> jobs;

	private StatisticFunction statFunc;

	protected int numNotStartedJobs, numFinishedJobs, numSuccessfulJobs, numRunningJobs;

	private boolean finished;
	
	private int number;
	
	/**
	 * Common initialization
	 */
	private SolverConfiguration() {
		jobs = new LinkedList<ExperimentResult>();
		totalRuntime = 0.f;
	}

	public SolverConfiguration(int idSolverConfiguration, ParameterConfiguration pc, StatisticFunction statFunc) {
		this();

		this.pConfig = pc;
		this.idSolverConfiguration = idSolverConfiguration;
		this.cost = null;
		this.nameRacing = null;
		this.nameSearch = null;
		this.nameUpdated = false;
		this.wasBest = false;
		this.statFunc = statFunc;
		this.incumbentNumber = -1;
		this.finished = false;
		this.number = -1;
	}
	
	public SolverConfiguration(int idSolverConfiguration, ParameterConfiguration pc, StatisticFunction statFunc, double dbCost) {
		this();
		this.dbCost = dbCost;
		this.pConfig = pc;
		this.idSolverConfiguration = idSolverConfiguration;
		this.cost = null;
		this.nameRacing = null;
		this.nameSearch = null;
		this.nameUpdated = false;
		this.wasBest = false;
		this.statFunc = statFunc;
		this.incumbentNumber = -1;
		this.finished = false;
		this.number = -1;
	}

	public SolverConfiguration(SolverConfiguration sc) {
		this();

		this.pConfig = new ParameterConfiguration(sc.pConfig);
		this.idSolverConfiguration = sc.idSolverConfiguration;
		this.cost = sc.cost;
		this.nameRacing = sc.nameRacing;
		this.nameSearch = sc.nameSearch;
		this.nameUpdated = sc.nameUpdated;
		this.wasBest  = sc.wasBest;
		this.statFunc = sc.statFunc;
		this.incumbentNumber = sc.incumbentNumber;
		this.finished = sc.finished;
		this.number = sc.number;
	}

	public int getIncumbentNumber() {
		return incumbentNumber;
	}

	public void setIncumbentNumber(int incumbentNumber) {
		this.incumbentNumber = incumbentNumber;
	}

	public final ParameterConfiguration getParameterConfiguration() {
		return this.pConfig;
	}

	public final void setParameterConfiguration(ParameterConfiguration pc) {
		this.pConfig = pc;
	}

	public final int getIdSolverConfiguration() {
		return this.idSolverConfiguration;
	}

	public final void setIdSolverConfiguration(int id) {
		this.idSolverConfiguration = id;
	}

	/**
	 * Computes the cost of the solver configuration according to the
	 * statistical function that the solver configuration was initialized. Only
	 * jobs that are finished are taken into considertion; running, unstarted or
	 * crashed jobs are ignored
	 * 
	 * @return the cost value with regards to the statistic function
	 */
	public final Double getCost() {
		return statFunc.getCostFunction().calculateCost(jobs);
	}

	/**
	 * Computes the cost of the solver configuration according to the
	 * statistical function that the solver configuration was initialized. Only
	 * jobs that are finished are taken into considertion; running, unstarted or
	 * crashed jobs are ignored
	 * 
	 * @return the cost value with regards to the statistic function
	 */
	public final Double getCumulatedCost() {
		return statFunc.getCostFunction().calculateCumulatedCost(jobs);
	}

	public final void setCost(Double cost) {
		this.cost = cost;
	}

	public final String getName() {
		if (nameRacing == null) {
			return nameSearch;
		} else if (nameSearch == null) {
			return nameRacing;
		}
		return nameSearch + " " + nameRacing;
	}

	public final void setNameRacing(String name) {
		this.nameRacing = name;
		this.nameUpdated = true;
	}
	
	public final void setNameSearch(String name) {
		this.nameSearch = name;
		this.nameUpdated = true;
	}

	public void putJob(ExperimentResult job) {
                if((job != null) && !jobs.contains(job))
                    jobs.add(job);
	}

	/**
	 * Returns the all jobs at the last <code>updateJobs()</code> call.<br/>
	 * 
	 * @return
	 */
	public List<ExperimentResult> getJobs() {
		LinkedList<ExperimentResult> res = new LinkedList<ExperimentResult>();
		for (ExperimentResult j : jobs) {
			res.add(j);
		}
		return res;
	}

	/**
	 * Returns the running jobs at the last <code>updateJobs()</code> call. <br/>
	 * Running jobs means, <code>statusCode</code> equals
	 * <code>StatusCode.RUNNING</code>.
	 * 
	 * @return
	 */
	public List<ExperimentResult> getRunningJobs() {
		LinkedList<ExperimentResult> res = new LinkedList<ExperimentResult>();
		for (ExperimentResult j : jobs) {
			if (j.getStatus().equals(StatusCode.RUNNING)) {
				res.add(j);
			}
		}
		return res;
	}

	/**
	 * Returns the number of running jobs at the last <code>updateJobs()</code>
	 * call. <br/>
	 * Running jobs means, <code>statusCode</code> equals
	 * <code>StatusCode.RUNNING</code>.
	 * 
	 * @return
	 */
	public int getNumRunningJobs() {
		return numRunningJobs;
	}

	/**
	 * Returns the finished jobs at the last <code>updateJobs()</code> call. <br/>
	 * Finished jobs means, <code>statusCode</code> is different to
	 * <code>StatusCode.NOT_STARTED</code> and <code>StatusCode.RUNNING</code>.
	 * 
	 * @return
	 */
	public List<ExperimentResult> getFinishedJobs() {
		LinkedList<ExperimentResult> res = new LinkedList<ExperimentResult>();
		for (ExperimentResult j : jobs) {
			if (!j.getStatus().equals(StatusCode.NOT_STARTED) && !j.getStatus().equals(StatusCode.RUNNING)) {
				res.add(j);
			}
		}
		return res;
	}

	/**
	 * Returns the finished jobs at the last <code>updateJobs()</code> call. <br/>
	 * Finished jobs means, <code>statusCode</code> is different to
	 * <code>StatusCode.NOT_STARTED</code> and <code>StatusCode.RUNNING</code>.
	 * 
	 * @return
	 */
	public int getNumFinishedJobs() {
		return numFinishedJobs;
	}

	/**
	 * Returns the not started jobs at the last <code>updateJobs()</code> call.<br/>
	 * Not started jobs means, <code>statusCode</code> is equal to
	 * <code>StatusCode.NOT_STARTED</code>.
	 * 
	 * @return
	 */
	public List<ExperimentResult> getNotStartedJobs() {
		LinkedList<ExperimentResult> res = new LinkedList<ExperimentResult>();
		for (ExperimentResult j : jobs) {
			if (j.getStatus().equals(StatusCode.NOT_STARTED)) {
				res.add(j);
			}
		}
		return res;
	}

	/**
	 * Returns the number of not started jobs at the last
	 * <code>updateJobs()</code> call.<br/>
	 * Not started jobs means, <code>statusCode</code> is equal to
	 * <code>StatusCode.NOT_STARTED</code>.
	 * 
	 * @return
	 */
	public int getNumNotStartedJobs() {
		return numNotStartedJobs;
	}

	public int getNumSuccessfulJobs() {
		return numSuccessfulJobs;
	}

	/**
	 * Returns the number of jobs for this solver configuration at the last
	 * <code>updateJobs()</code> call.
	 * 
	 * @return
	 */
	public int getJobCount() {
		return jobs.size();
	}

	/**
	 * Updates the locally cached jobs for this solver configuration. It
	 * collects the id's of its own jobs and passes them to the api as a list of
	 * id's getting back the ExperimentResults list for this id's
	 * 
	 * @return the cpu time needed since the last updateJobsStatus()-call
	 * @throws Exception
	 */

	protected List<ExperimentResult> updateJobsStatus(API api) throws Exception {
		List<ExperimentResult> res = new LinkedList<ExperimentResult>();
		LinkedList<Integer> ids = new LinkedList<Integer>();
		ArrayList<ExperimentResult> tmp = new ArrayList<ExperimentResult>();
		numRunningJobs = 0;
		numFinishedJobs = 0;
		numSuccessfulJobs = 0;
		numNotStartedJobs = 0;
		totalRuntime = 0.f;
		for (ExperimentResult j : jobs) {
			if (!j.getStatus().equals(StatusCode.NOT_STARTED) && !j.getStatus().equals(StatusCode.RUNNING)) {
				numFinishedJobs++;
				totalRuntime += j.getResultTime();
				if (String.valueOf(j.getResultCode().getResultCode()).startsWith("1")) {
					numSuccessfulJobs++;
				}
				tmp.add(j);
			} else {
				ids.add(j.getId());
			}
		}

		if (!ids.isEmpty()) {
			jobs.clear();
			jobs.addAll(api.getJobsByIDs(ids).values());
			
			for (ExperimentResult j : jobs) {
				totalRuntime += j.getResultTime();
				if (j.getStatus().equals(StatusCode.RUNNING)) {
					numRunningJobs++;
				}
				if (!j.getStatus().equals(StatusCode.NOT_STARTED) && !j.getStatus().equals(StatusCode.RUNNING)) {
					res.add(j);
					numFinishedJobs++;
				}
				if (String.valueOf(j.getResultCode().getResultCode()).startsWith("1")) {
					numSuccessfulJobs++;
				}
				if (j.getStatus().equals(StatusCode.NOT_STARTED)) {
					numNotStartedJobs++;
				}
			}
			jobs.addAll(tmp);
			
		} 
		return res;
	}

	/**
	 * Returns a list of <code>InstanceIdSeed</code> where each entry satisfies: <br/>
	 * * this solver configuration has computed/computes/will compute the
	 * instance-seed-pair<br/>
	 * * the <code>other</code> solver configuration did not compute/is not
	 * currently computing/will not compute the instance-seed-pair<br/>
	 * * the list contains max. <code>num</code> items<br/>
	 * at the last <code>updateJobs()</code> call.<br/>
	 * If the list contains less than <code>num</code> items, then there aren't
	 * more instance-seed-pairs which would satisfy the first two assertions.
	 * 
	 * @param other
	 * @param num
	 * @return
	 */
	public List<InstanceIdSeed> getInstanceIdSeed(SolverConfiguration other, int num, Random rng) {
		LinkedList<InstanceIdSeed> all = new LinkedList<InstanceIdSeed>();
		HashSet<InstanceIdSeed> ownInstanceIdSeed = new HashSet<InstanceIdSeed>();
		for (ExperimentResult j : jobs) {
			ownInstanceIdSeed.add(new InstanceIdSeed(j.getInstanceId(), j.getSeed()));
		}
		for (ExperimentResult j : other.getFinishedJobs()) {
			InstanceIdSeed tmp = new InstanceIdSeed(j.getInstanceId(), j.getSeed());
			if (!ownInstanceIdSeed.contains(tmp)) {
				all.add(tmp);
			}
		}

		if (all.size() <= num) {
			return all;
		} else {
			LinkedList<InstanceIdSeed> res = new LinkedList<InstanceIdSeed>();
			while (res.size() < num) {
				int index = rng.nextInt(all.size());
				res.add(all.get(index));
				all.remove(index);
			}
			return res;
		}

	}
	
	   /**
     * Returns a list of <code>InstanceIdSeed</code> where each entry satisfies: <br/>
     * * this solver configuration has computed/computes/will compute the
     * instance-seed-pair<br/>
     * * the <code>other</code> solver configuration did not compute/is not
     * currently computing/will not compute the instance-seed-pair<br/>
     * * the list contains max. <code>num</code> items<br/>
     * at the last <code>updateJobs()</code> call.<br/>
     * If the list contains less than <code>num</code> items, then there aren't
     * more instance-seed-pairs which would satisfy the first two assertions.
     * 
     * @param other
     * @param num
     * @return
     */
    public List<InstanceIdSeed> getInstanceIdSeedAggressive(SolverConfiguration other, int num, Random rng) {
        LinkedList<InstanceIdSeed> allUnpenalized = new LinkedList<InstanceIdSeed>();
        LinkedList<InstanceIdSeed> allPenalized = new LinkedList<InstanceIdSeed>();
        HashSet<InstanceIdSeed> ownInstanceIdSeed = new HashSet<InstanceIdSeed>();
        List<ExperimentResult> otherFinishedJobs = other.getFinishedJobs();
        Collections.sort(otherFinishedJobs, new Comparator<ExperimentResult>() {
            @Override
            public int compare(ExperimentResult o1, ExperimentResult o2) {
                double y1 = statFunc.getCostFunction().singleCost(o1);
                double y2 = statFunc.getCostFunction().singleCost(o2);
                return statFunc.isMinimize() ? Double.compare(y1, y2) : Double.compare(y2, y1);
            }
        });
        for (ExperimentResult j : jobs) {
            ownInstanceIdSeed.add(new InstanceIdSeed(j.getInstanceId(), j.getSeed()));
        }
        for (ExperimentResult j : otherFinishedJobs) {
            InstanceIdSeed tmp = new InstanceIdSeed(j.getInstanceId(), j.getSeed());
            if (!ownInstanceIdSeed.contains(tmp)) {
                if (statFunc.getCostFunction().isSingleCostPenalized(j)) {
                    allPenalized.add(tmp);
                } else {
                    allUnpenalized.add(tmp);
                }
            }
        }

        if (allUnpenalized.size() + allPenalized.size() <= num) {
            List<InstanceIdSeed> all = new LinkedList<InstanceIdSeed>();
            all.addAll(allUnpenalized);
            all.addAll(allPenalized);
            return all;
        } else {
            LinkedList<InstanceIdSeed> res = new LinkedList<InstanceIdSeed>();
            // first take unpenalized jobs
            while (res.size() < num && !allUnpenalized.isEmpty()) {
                int index = rng.nextInt(allUnpenalized.size());
                res.add(allUnpenalized.get(index));
                allUnpenalized.remove(index);
            }
            // then penalized jobs if necessary
            while (res.size() < num) {
                int index = rng.nextInt(allPenalized.size());
                res.add(allPenalized.get(index));
                allPenalized.remove(index);
            }
            return res;
        }

    }

	public Float getTotalRuntime() {
		return totalRuntime;
	}

	public boolean isFinished() {
		return finished;
	}

	public void setFinished(boolean finished) {
		this.finished = finished;
	}

	public int getNumber() {
		return number;
	}

	public void setNumber(int number) {
		this.number = number;
	}
	

	@Override
	public int compareTo(SolverConfiguration other) {
		HashMap<InstanceIdSeed, ExperimentResult> ownJobs = new HashMap<InstanceIdSeed, ExperimentResult>();
		for (ExperimentResult job : getFinishedJobs()) {
			ownJobs.put(new InstanceIdSeed(job.getInstanceId(), job.getSeed()), job);
		}
		List<ExperimentResult> myJobs = new LinkedList<ExperimentResult>();
		List<ExperimentResult> otherJobs = new LinkedList<ExperimentResult>();
		for (ExperimentResult job : other.getFinishedJobs()) {
			InstanceIdSeed tmp = new InstanceIdSeed(job.getInstanceId(), job.getSeed());
			ExperimentResult ownJob;
			if ((ownJob = ownJobs.get(tmp)) != null) {
				myJobs.add(ownJob);
				otherJobs.add(job);
			}
		}
		return statFunc.compare(myJobs, otherJobs);
	}

	protected void jobReset(ExperimentResult er) {
		if (er.getStatus().equals(StatusCode.RUNNING)) {
			numRunningJobs--;
		}
		if (!er.getStatus().equals(StatusCode.NOT_STARTED) && !er.getStatus().equals(StatusCode.RUNNING)) {
			numFinishedJobs--;
		}
		if (String.valueOf(er.getResultCode().getResultCode()).startsWith("1")) {
			numSuccessfulJobs--;
		}
		if (!er.getStatus().equals(StatusCode.NOT_STARTED)) {
			numNotStartedJobs++;
		}
		er.setStatus(StatusCode.NOT_STARTED);
		er.setResultCode(ResultCode.UNKNOWN);
	}
}

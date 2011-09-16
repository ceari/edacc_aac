package edacc.configurator.proar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import edacc.parameterspace.ParameterConfiguration;
import edacc.model.ExperimentResult;
import edacc.model.ExperimentResultDAO;
import edacc.model.StatusCode;

public class SolverConfiguration implements Comparable<SolverConfiguration> {
	/** the parameter configuration of the solver configuration */
	private ParameterConfiguration pConfig;

	/** id of the solver configuration from the DB */
	private int idSolverConfiguration;

	/**
	 * the cost of the configuration with regards to a statistic function and a
	 * metric (cost or runtime);
	 */
	private Float cost;

	/**
	 * total runtime in seconds of all the jobs in the jobs list
	 * 
	 */
	private Float totalRuntime;

	/** the name of the configuration */
	private String name;

	/**
	 * iteration of the configurator where this configuration was created;
	 * useful for debugging
	 */
	private int level;

	/** List of all jobs that a solver configuration has been executed so far */
	private List<ExperimentResult> jobs;

	private StatisticFunction statFunc;

	/**
	 * Common initialization
	 */
	private SolverConfiguration() {
		jobs = new LinkedList<ExperimentResult>();
	}

	public SolverConfiguration(int idSolverConfiguration, ParameterConfiguration pc, StatisticFunction statFunc,
			int level) {
		this();

		this.pConfig = pc;
		this.idSolverConfiguration = idSolverConfiguration;
		this.cost = null;
		this.name = null;
		this.statFunc = statFunc;
		this.level = level;
	}

	public SolverConfiguration(SolverConfiguration sc) {
		this();

		this.pConfig = new ParameterConfiguration(sc.pConfig);
		this.idSolverConfiguration = sc.idSolverConfiguration;
		this.cost = sc.cost;
		this.name = sc.name;
		this.statFunc = sc.statFunc;
		this.level = sc.level;
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
	public final Float getCost() {
		return statFunc.getCostFunction().calculateCost(jobs);
	}

	public final void setCost(Float cost) {
		this.cost = cost;
	}

	public final String getName() {
		return this.name;
	}

	public final void setName(String name) {
		this.name = name;
	}

	public void putJob(ExperimentResult job) {
		jobs.add(job);
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
	 * @throws Exception
	 */
	LinkedList<Integer> ids = new LinkedList<Integer>();

	public void updateJobsStatus() throws Exception {
		for (ExperimentResult j : jobs) {
			ids.add(j.getId());
		}
		jobs = ExperimentResultDAO.getByIds(ids);
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
	 * Returns the <code>level</code> of this solver configuration.
	 * 
	 * @return
	 */
	public int getLevel() {
		return level;
	}

	@Override
	public int compareTo(SolverConfiguration other) {

		// TODO : metrik (sollte automatisch durch API gegeben sein -> TODO f�r
		// api) in betracht ziehen!

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
}

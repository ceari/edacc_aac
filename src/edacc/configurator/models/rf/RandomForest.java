package edacc.configurator.models.rf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edacc.api.API;
import edacc.api.costfunctions.CostFunction;
import edacc.api.costfunctions.PARX;
import edacc.configurator.aac.SolverConfiguration;
import edacc.configurator.aac.util.RInterface;
import edacc.configurator.math.PCA;
import edacc.model.Experiment;
import edacc.model.ExperimentDAO;
import edacc.model.ExperimentResult;
import edacc.model.Instance;
import edacc.model.InstanceDAO;
import edacc.model.InstanceHasProperty;
import edacc.model.Experiment.Cost;
import edacc.parameterspace.Parameter;
import edacc.parameterspace.ParameterConfiguration;
import edacc.parameterspace.domain.CategoricalDomain;
import edacc.parameterspace.domain.FlagDomain;
import edacc.parameterspace.domain.IntegerDomain;
import edacc.parameterspace.domain.OrdinalDomain;
import edacc.parameterspace.domain.RealDomain;
import edacc.parameterspace.graph.ParameterGraph;

public class RandomForest {
    private CensoredRandomForest rf;
    private List<Parameter> configurableParameters = new ArrayList<Parameter>();
    private CostFunction par1CostFunc;
    private boolean logModel;
    private double[][] instanceFeatures;
    private Map<Integer, Integer> instanceFeaturesIx = new HashMap<Integer, Integer>();
    private List<String> instanceFeatureNames = new LinkedList<String>();

    /**
     * Initialize a random forest.
     * @param api Instance of the EDACC API
     * @param idExperiment ID of the configuration experiment for which the random forest is built
     * @param logModel whether to use a log10 transformation of cost values or not
     * @param nTrees number of trees in the forest
     * @param rng RNG instance used in the RF construction
     * @param CPUTimeLimit maximum CPU time for any run in the experiment (if cpu time is the target)
     * @param wallClockLimit maximum wall time for any run in the experiment (if wall time is the target)
     * @throws Exception
     */
    public RandomForest(API api, int idExperiment, boolean logModel, int nTrees, Random rng, int CPUTimeLimit, int wallClockLimit, List<String> additionalInstanceFeatureNames) throws Exception {
        this.logModel = logModel;
        ParameterGraph pspace = api.loadParameterGraphFromDB(idExperiment);
        
        this.instanceFeatureNames = new LinkedList<String>(additionalInstanceFeatureNames);
        instanceFeatureNames.add("instance-index");
        
        List<Instance> instances;
        // Load instance features
        instances = InstanceDAO.getAllByExperimentId(idExperiment);
        instanceFeatures = new double[instances.size()][instanceFeatureNames.size()];
        for (Instance instance: instances) {
            instanceFeaturesIx.put(instance.getId(), instances.indexOf(instance));
            
            Map<String, Float> featureValueByName = new HashMap<String, Float>();
            for (InstanceHasProperty ihp: instance.getPropertyValues().values()) {
                if (!instanceFeatureNames.contains(ihp.getProperty().getName())) continue;
                try {
                    featureValueByName.put(ihp.getProperty().getName(), Float.valueOf(ihp.getValue()));
                } catch (Exception e) {
                    throw new Exception("All instance features have to be numeric (convertible to a Java Float).");
                }
            }
            
            featureValueByName.put("instance-index", Float.valueOf(instances.indexOf(instance)));
            
            for (String featureName: instanceFeatureNames) {
                instanceFeatures[instances.indexOf(instance)][instanceFeatureNames.indexOf(featureName)] = featureValueByName.get(featureName);
            }
        }
        
        // Project instance features into lower dimensional space using PCA
        PCA pca = new PCA(RInterface.getRengine());
        instanceFeatures = pca.transform(instanceFeatures.length, instanceFeatureNames.size(), instanceFeatures, 7);
        
        int numFeatures = instanceFeatureNames.size();
        instanceFeatureNames.clear();
        for (int i = 0; i < Math.min(7, numFeatures); i++) instanceFeatureNames.add("PC" + (i+1)); // rename instance features to reflect PCA transformation
    
        
        // Load information about the parameter space
        configurableParameters.addAll(api.getConfigurableParameters(idExperiment));
        int[] catDomainSizes = new int[configurableParameters.size() + instanceFeatureNames.size()];
        for (Parameter p: configurableParameters) {
            if (p.getDomain() instanceof FlagDomain) {
                catDomainSizes[configurableParameters.indexOf(p)] = 2;
            }
            else if (p.getDomain() instanceof CategoricalDomain || p.getDomain() instanceof OrdinalDomain) {
                catDomainSizes[configurableParameters.indexOf(p)] = p.getDomain().getDiscreteValues().size();
            }
        }
        
        double kappaMax = 0;
        if (ExperimentDAO.getById(idExperiment).getDefaultCost().equals(Cost.resultTime)) {
            kappaMax = CPUTimeLimit;
            par1CostFunc = new PARX(Experiment.Cost.resultTime, true, 1.0f);
        } else if (ExperimentDAO.getById(idExperiment).getDefaultCost().equals(Cost.wallTime)) {
            kappaMax = wallClockLimit;
            par1CostFunc = new PARX(Experiment.Cost.wallTime, true, 1.0f);
        } else {
            kappaMax = ExperimentDAO.getById(idExperiment).getCostPenalty();
            par1CostFunc = new PARX(Experiment.Cost.cost, true, 1.0f);
        }
        
        int[][] condParents = null;
        int[][][] condParentVals = null;
        pspace.conditionalParentsForRF(configurableParameters, condParents, condParentVals);
        
        rf = new CensoredRandomForest(nTrees, logModel ? 1 : 0, kappaMax, 1.0, catDomainSizes, rng, condParents, condParentVals);
    }
    
    /**
     * Learn the model from the results of the passed list of configs.
     * @param configs
     * @throws Exception
     */
    public void learnModel(List<SolverConfiguration> configs) throws Exception {
        double[][] theta = new double[configs.size()][];
        Map<SolverConfiguration, Integer> solverConfigTheta = new HashMap<SolverConfiguration, Integer>();
        int countJobs = 0;
        int cIx = 0;
        for (SolverConfiguration config: configs) {
            solverConfigTheta.put(config, cIx);
            theta[cIx] = paramConfigToTuple(config.getParameterConfiguration());
            countJobs += config.getFinishedJobs().size();
            cIx++;
        }

        int[][] theta_inst_idxs = new int[countJobs][2];
        boolean[] censored = new boolean[countJobs];
        double[] y = new double[countJobs];
        
        int jIx = 0;
        for (SolverConfiguration config: configs) {
            for (ExperimentResult run: config.getFinishedJobs()) {
                theta_inst_idxs[jIx][0] = solverConfigTheta.get(config);
                theta_inst_idxs[jIx][1] = instanceFeaturesIx.get(run.getInstanceId());
                censored[jIx] = !run.getResultCode().isCorrect();
                y[jIx] = par1CostFunc.singleCost(run);
                if (logModel) {
                    if (y[jIx] <= 0) {
                        System.out.println("(!!!!!) WARNING log model with values <= 0.");
                        y[jIx] = 1e-15;
                    }
                    y[jIx] = Math.log10(y[jIx]);
                }
                jIx++;
            }
        }
        
        rf.learnModel(theta, instanceFeatures, configurableParameters.size(), instanceFeatureNames.size(), theta_inst_idxs, y, censored);
    }

    /**
     * Predict the mean performance and prediction variance of the supplied list of parameter
     * configurations over all instances of the experiment.
     * @param configs
     * @return Array of <code>configs.size()</code>-many double[2], i.e. [configNum][0] is the predicted mean, [configNum][1] the variance
     */
    public double[][] predict(List<ParameterConfiguration> configs) {
        double[][] thetas = new double[configs.size()][];
        int ix = 0;
        for (ParameterConfiguration config: configs) thetas[ix] = paramConfigToTuple(config);
        return rf.predict(thetas);
    }
    
    /**
     * Predict the mean performance and prediction variance of the supplied list of parameter
     * configurations, over the specified list of instances.
     * @param configs
     * @param instances
     * @return see predict()
     */
    public double[][] predictMarginal(List<ParameterConfiguration> configs, List<Instance> instances) {
        double[][] thetas = new double[configs.size()][];
        int ix = 0;
        for (ParameterConfiguration config: configs) thetas[ix++] = paramConfigToTuple(config);
        int[] inst_idxs = new int[instances.size()];
        ix = 0;
        for (Instance i: instances) inst_idxs[ix++] = instanceFeaturesIx.get(i.getId()); 
        return rf.predictMarginal(thetas, inst_idxs);
    }
    
    /**
     * Calculate variable importance measures. The first values correspond
     * to the configurable parameters (getConfigurableParameters) and then PCA-ed
     * instance features follow.
     * @return
     */
    public double[] getVI() {
        return rf.calculateVI();
    }
    
    /**
     * Calculate validation error (average out-of-bag residual sum of squares)
     * @return
     */
    public double getOOBRSS() {
        return rf.calculateOobRSS();
    }
    
    /**
     * Return an ordered list of configurable parameters.
     * @return
     */
    public List<Parameter> getConfigurableParameters() {
        return new LinkedList<Parameter>(configurableParameters);
    }
    
    
    private double[] paramConfigToTuple(ParameterConfiguration paramConfig) {
        double[] theta = new double[configurableParameters.size()];
        for (Parameter p: configurableParameters) {
            int pIx = configurableParameters.indexOf(p);
            Object paramValue = paramConfig.getParameterValue(p);
            if (paramValue == null) theta[pIx] = Double.NaN;
            else {
                if (p.getDomain() instanceof RealDomain) {
                    theta[pIx] = (Double)paramValue;
                } else if (p.getDomain() instanceof IntegerDomain) {
                    theta[pIx] = (Integer)paramValue;
                } else if (p.getDomain() instanceof CategoricalDomain) {
                    // map categorical parameters to integers 1 through domain.size, 0 = not set
                    Map<String, Integer> valueMap = new HashMap<String, Integer>();
                    int intVal = 1;
                    List<String> sortedValues = new LinkedList<String>(((CategoricalDomain)p.getDomain()).getCategories());
                    Collections.sort(sortedValues);
                    for (String val: sortedValues) {
                        valueMap.put(val, intVal++);
                    }
                    
                    theta[pIx] = valueMap.get((String)paramValue);
                } else if (p.getDomain() instanceof OrdinalDomain) {
                    // map ordinal parameters to integers 1 through domain.size, 0 = not set
                    Map<String, Integer> valueMap = new HashMap<String, Integer>();
                    int intVal = 1;
                    for (String val: ((OrdinalDomain)p.getDomain()).getOrdered_list()) {
                        valueMap.put(val, intVal++);
                    }
                    
                    theta[pIx] = valueMap.get((String)paramValue);
                } else if (p.getDomain() instanceof FlagDomain) {
                    // map flag parameters to {0, 1}
                    if (FlagDomain.FLAGS.ON.equals(paramValue)) theta[pIx] = 1;
                    else theta[pIx] = 0;
                } else {
                    // TODO
                    theta[pIx] = paramValue.hashCode();
                }
            }
            
        }
        
        return theta;
    }
}
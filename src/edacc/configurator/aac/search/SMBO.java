package edacc.configurator.aac.search;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.math.MathException;
import org.apache.commons.math3.distribution.ExponentialDistribution;

import edacc.api.API;
import edacc.api.costfunctions.CostFunction;
import edacc.api.costfunctions.PARX;
import edacc.configurator.aac.AAC;
import edacc.configurator.aac.Parameters;
import edacc.configurator.aac.SolverConfiguration;
import edacc.configurator.aac.racing.FRace;
import edacc.configurator.aac.racing.SMFRace;
import edacc.configurator.aac.racing.DefaultSMBO;
import edacc.configurator.aac.search.ibsutils.SolverConfigurationIBS;
import edacc.configurator.aac.util.RInterface;
import edacc.configurator.math.PCA;
import edacc.configurator.math.SamplingSequence;
import edacc.configurator.models.rf.CensoredRandomForest;
import edacc.configurator.models.rf.fastrf.utils.Gaussian;
import edacc.configurator.models.rf.fastrf.utils.Utils;
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

/*
 * TODO:
 * - revisit known configurations (when the EI optimization gives an already evaluated configuration)
 */
public class SMBO extends SearchMethods {
    private ParameterGraph pspace;
    private Set<SolverConfiguration> generatedConfigs = new HashSet<SolverConfiguration>();
    private List<Parameter> configurableParameters = new ArrayList<Parameter>();
    private List<String> instanceFeatureNames = new ArrayList<String>();
    private List<Instance> instances;
    private Map<Integer, Integer> instanceFeaturesIx = new HashMap<Integer, Integer>();
    private double[][] instanceFeatures;
    private List<SolverConfiguration> configurationQueue = new LinkedList<SolverConfiguration>();
    private List<SolverConfiguration> initialDesignConfigs = new LinkedList<SolverConfiguration>();
    private Set<ParameterConfiguration> allSelectedConfigs = new HashSet<ParameterConfiguration>();
    
    private final int maxSamples = 100000;
    private SamplingSequence sequence;
    private double sequenceValues[][];
    
    private CensoredRandomForest model;
    
    private CostFunction par1CostFunc;
    private int randomSeqNum = 0;
    private boolean canUseFastMethods = false;
    private int statNumBestRandom = 0;
    private int statTotalOptimizations = 0;
    
    // Configurable parameters
    private boolean logModel = true;
    private String selectionCriterion = "ocb"; // ei, ocb
    private int numPC = 13;
    private int numInitialConfigurationsFactor = 20; // how many samples per parameter initially
    private int numRandomTheta = 10000; // how many random theta to predict for EI/OCB optimization
    private int maxLocalSearchSteps = 5;
    private float lsStddev = 0.001f; // stddev used in LS sampling
    private int lsSamples = 10; // how many samples per parameter for the LS neighbourhood
    private int nTrees = 10;
    private double ocbExpMu = 1;
    private int EIg = 2; // global search factor {1,2,3}
    private boolean useInstanceIndexFeature = true; // simply use the index of an instance as instance feature
    private int queueSize = 40; // how many configurations to generate at a time and put into a queue
    private boolean createIBSConfigs = false;
    private boolean initialDesignFromDefault = true;
    private int numTopLS = 10;
    private int numTopSel = 3;

    private String samplingPath = "";
    private String featureFolder = null;
    private String featureCacheFolder = null;
    
    private int numProcs = 1; // number of threads to use when optimizing selection criteria

    public SMBO(AAC pacc, API api, Random rng, Parameters parameters, List<SolverConfiguration> firstSCs, List<SolverConfiguration> referenceSCs) throws Exception {
        super(pacc, api, rng, parameters, firstSCs, referenceSCs);
        
        parseSMBOParameters();
        
        pspace = api.loadParameterGraphFromDB(parameters.getIdExperiment());
        ParameterGraph.calculateChecksums = false;
        
        if (featureFolder != null) {
            for (String feature: AAC.getFeatureNames(new File(featureFolder))) instanceFeatureNames.add(feature);
        } else {
            // TODO: Load from configuration?
            //instanceFeatureNames.add("POSNEG-RATIO-CLAUSE-mean");
        }
        
        if (useInstanceIndexFeature) instanceFeatureNames.add("instance-index");
        
        // Load instance features
        instances = InstanceDAO.getAllByExperimentId(parameters.getIdExperiment());
        instanceFeatures = new double[instances.size()][instanceFeatureNames.size()];
        for (Instance instance: instances) {
            instanceFeaturesIx.put(instance.getId(), instances.indexOf(instance));
            Map<String, Float> featureValueByName = new HashMap<String, Float>();
            
            if (featureFolder != null) {
                float[] featureValues = AAC.calculateFeatures(instance.getId(), new File(featureFolder), new File(featureCacheFolder));
                for (int i = 0; i < featureValues.length; i++) {
                    featureValueByName.put(instanceFeatureNames.get(i), featureValues[i]);
                }
            } else {
                for (InstanceHasProperty ihp: instance.getPropertyValues().values()) {
                    if (!instanceFeatureNames.contains(ihp.getProperty().getName())) continue;
                    try {
                        featureValueByName.put(ihp.getProperty().getName(), Float.valueOf(ihp.getValue()));
                    } catch (Exception e) {
                        throw new Exception("All instance features have to be numeric (convertible to a Java Float).");
                    }
                }
            }
            
            if (useInstanceIndexFeature) featureValueByName.put("instance-index", Float.valueOf(instances.indexOf(instance)));
            
            for (String featureName: instanceFeatureNames) {
                instanceFeatures[instances.indexOf(instance)][instanceFeatureNames.indexOf(featureName)] = featureValueByName.get(featureName);
            }
        }
        
        // Project instance features into lower dimensional space using PCA
        PCA pca = new PCA(RInterface.getRengine());
        instanceFeatures = pca.transform(instanceFeatures.length, instanceFeatureNames.size(), instanceFeatures, numPC);
        pacc.log("c Using " + instanceFeatures[0].length + " instance features of " + instanceFeatures.length + " instances");
        double[][] pcaFeatures = new double[instanceFeatures.length][instanceFeatures[0].length];
        for (int i = 0; i < instanceFeatures.length; i++) {
            for (int j = 0; j < instanceFeatures[0].length; j++) {
                //System.out.print(instanceFeatures[i][j] + " ");
                pcaFeatures[i][j] = instanceFeatures[i][j];
            }
            //System.out.println();
        }
        instanceFeatures = pcaFeatures;
        
        //int numFeatures = instanceFeatureNames.size();
        instanceFeatureNames.clear();
        for (int i = 0; i < instanceFeatures[0].length; i++) instanceFeatureNames.add("PC" + (i+1)); // rename instance features to reflect PCA transformation
        
        // Load information about the parameter space
        configurableParameters.addAll(api.getConfigurableParameters(parameters.getIdExperiment()));
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
        if (ExperimentDAO.getById(parameters.getIdExperiment()).getDefaultCost().equals(Cost.resultTime)) {
            kappaMax = parameters.getJobCPUTimeLimit();
            par1CostFunc = new PARX(Experiment.Cost.resultTime, true, kappaMax, 1);
        } else if (ExperimentDAO.getById(parameters.getIdExperiment()).getDefaultCost().equals(Cost.wallTime)) {
            kappaMax = parameters.getJobWallClockTimeLimit();
            par1CostFunc = new PARX(Experiment.Cost.wallTime, true, kappaMax, 1);
        } else {
            kappaMax = ExperimentDAO.getById(parameters.getIdExperiment()).getCostPenalty();
            par1CostFunc = new PARX(Experiment.Cost.cost, true, kappaMax, 1);
        }

        // Get conditional parameter structure for the random forest implementation
        Object[] cpRF = pspace.conditionalParentsForRF(configurableParameters);
        int[][] condParents = (int[][])cpRF[0];
        int[][][] condParentVals = (int[][][])cpRF[1];
        int[][] augmentedCondParents = new int[condParents.length + instanceFeatureNames.size()][];
        for (int i = 0; i < condParents.length; i++) augmentedCondParents[i] = condParents[i];
        condParents = augmentedCondParents;
        
        for (int i = 0; i < configurableParameters.size(); i++) {
            System.out.println("Conditional parents of " + configurableParameters.get(i));
            if (condParents[i] == null) {
                System.out.println("None"); continue;
            }
            for (int j = 0; j < condParents[i].length; j++) {
                System.out.println(configurableParameters.get(condParents[i][j]));
                for (int k = 0; k < condParentVals[i][j].length; k++) {
                    System.out.println(condParentVals[i][j][k]);
                }
            }
        }
        
        // If there are no conditional parameters use optimized parameter graph methods
        canUseFastMethods = true;
        for (int i = 0; i < condParents.length; i++) {
            if (condParents[i] != null) canUseFastMethods = false;
        }
        if (canUseFastMethods) {
            pacc.log("c All parameters unconditional.");
        }

        // Initialize the predictive model
        model = new CensoredRandomForest(nTrees, logModel ? 1 : 0, kappaMax, 1.0, catDomainSizes, rng, condParents, condParentVals);
        
        // Initialize pseudo-random sequence for the initial sampling
        sequence = new SamplingSequence(samplingPath);
        sequenceValues = sequence.getSequence(configurableParameters.size(), maxSamples);
        
        // Add default configurations to the search
        generatedConfigs.addAll(firstSCs);
        for (SolverConfiguration defaultConfig: firstSCs) {
            if (!pspace.validateParameterConfiguration(defaultConfig.getParameterConfiguration())) {
                pacc.log("e Default configuration " + defaultConfig.getName() + " does not conform to the parameter space specification");
                throw new RuntimeException("Invalid default configuration");
            }
            defaultConfig.getParameterConfiguration().updateChecksum();
            allSelectedConfigs.add(defaultConfig.getParameterConfiguration());
        }
        initialDesignConfigs.addAll(firstSCs);
        pacc.log("c Starting out with " + firstSCs.size() + " default configs");
        
        if (initialDesignFromDefault && firstSCs.size() > 0) {
            // Add neighbours of default configurations to the initial design
            List<ParameterConfiguration> defaultMutations = new LinkedList<ParameterConfiguration>();
            for (SolverConfiguration config: firstSCs) {
                defaultMutations.addAll(pspace.getGaussianNeighbourhood(config.getParameterConfiguration(), rng, 0.2f, 2, true));
            }
            pacc.log("c Using an initial design of " + defaultMutations.size() + " neighbours of the default configurations");
            
            for (ParameterConfiguration paramConfig: defaultMutations) {
                if (api.exists(parameters.getIdExperiment(), paramConfig) != 0) {
                    pacc.log("c WARNING selected configuration already exists. Skipping");
                    continue;
                }
                int idSC = api.createSolverConfig(parameters.getIdExperiment(), paramConfig, api.getCanonicalName(parameters.getIdExperiment(), paramConfig));
                SolverConfiguration cfg = null;
                if (createIBSConfigs) {
                    cfg = createIBSConfig(idSC, paramConfig);
                } else {
                    cfg = new SolverConfiguration(idSC, paramConfig, parameters.getStatistics());
                }
                generatedConfigs.add(cfg);
                configurationQueue.add(cfg);
                initialDesignConfigs.add(cfg);
                paramConfig.updateChecksum();
                allSelectedConfigs.add(paramConfig);
            }
        }
    }

    @Override
    public List<SolverConfiguration> generateNewSC(int num) throws Exception {
        if (num <= 0) return new LinkedList<SolverConfiguration>();
        
        if (generatedConfigs.size() < numInitialConfigurationsFactor * configurableParameters.size() && numInitialConfigurationsFactor > 0) {
            List<SolverConfiguration> rssConfigs = new LinkedList<SolverConfiguration>();
            if (pacc.racing instanceof FRace || pacc.racing instanceof SMFRace) {
                // FRace and SMFRace don't automatically use the old best configurations
                rssConfigs.addAll(pacc.racing.getBestSolverConfigurations(num));
            }
            int sampledConfigs = Math.min(num, numInitialConfigurationsFactor * configurableParameters.size() - generatedConfigs.size());
            // Start the search with an initial design of random configurations
            for (int i = 0; i < sampledConfigs; i++) {
                ParameterConfiguration pc = mapRealTupleToParameters(sequenceValues[randomSeqNum++]);
                while (pspace.validateParameterConfiguration(pc) == false) pc = mapRealTupleToParameters(sequenceValues[randomSeqNum++]);
                int idSC = api.createSolverConfig(parameters.getIdExperiment(), pc, "SN: " + randomSeqNum);
                rssConfigs.add(new SolverConfiguration(idSC, pc, parameters.getStatistics()));
            }
            generatedConfigs.addAll(rssConfigs);
            initialDesignConfigs.addAll(rssConfigs);
            return rssConfigs;
        }
        
        
        /*boolean initialDesignEvaluated = true;
        for (SolverConfiguration config: initialDesignConfigs) {
            if (config.getNumFinishedJobs() < parameters.getInitialDefaultParcoursLength()) initialDesignEvaluated = false;
        }
        if (!initialDesignEvaluated) {
            if (pacc.racing instanceof DefaultSMBO && ((DefaultSMBO)pacc.racing).initialDesignMode) {
                pacc.log("Waiting for initial design to be evaluated.");
                return new LinkedList<SolverConfiguration>();
            }
        }
        
        if (initialDesignEvaluated) {
            if (pacc.racing instanceof DefaultSMBO) {
                pacc.log("c Deactivating initialDesignMode of DefaultSMBO");
                ((DefaultSMBO)pacc.racing).initialDesignMode = false;
            }
        }*/
        
        
        List<SolverConfiguration> newConfigs = new ArrayList<SolverConfiguration>();
        
        if (pacc.racing instanceof FRace || pacc.racing instanceof SMFRace) {
            // FRace and SMFRace don't automatically use the old best configurations
            newConfigs.addAll(pacc.racing.getBestSolverConfigurations(num));
        }
        
        if (configurationQueue.size() < queueSize) {
            /*boolean allInitialDone = true;
            for (SolverConfiguration config: initialDesignConfigs) {
                if (config.getNumFinishedJobs() != config.getJobCount()) {
                    allInitialDone = false;
                }
            }
            if (!allInitialDone) {
                pacc.log("c Waiting until initial design is evaluated");
                return new LinkedList<SolverConfiguration>();
            }*/
            // refill queue
            int numConfigsToGenerate = queueSize;
            pacc.log("c Generating " + numConfigsToGenerate + " configurations to refill the queue.");
            pacc.log("c wall time: " + pacc.getWallTime() + "s, job CPU time: " + pacc.getCumulatedCPUTime() + "s");

            // Update the model
            int numFinishedJobs = 0;
            boolean anyUncensored = false;
            for (SolverConfiguration config: generatedConfigs) {
                numFinishedJobs += config.getNumFinishedJobs();
                for (ExperimentResult run: config.getFinishedJobs()) {
                    if (!par1CostFunc.isSingleCostPenalized(run)) anyUncensored = true;
                }
            }
            if (numFinishedJobs == 0 || !anyUncensored) {
                pacc.log("c There are no jobs finished yet, or only censored runs available. Waiting for initial design to be evaluated.");
                boolean allInitialConfigsFinished = true;
                for (SolverConfiguration sc: initialDesignConfigs) {
                    if (sc.getNumRunningJobs() > 0) allInitialConfigsFinished = false;
                }
                if (allInitialConfigsFinished) {
                    // If the initial design did not lead to any uncensored data, add a random configuration
                    List<SolverConfiguration> randomConfigs = new LinkedList<SolverConfiguration>();
                    for (int i = 0; i < num; i++) {
                        ParameterConfiguration paramConfig = pspace.getRandomConfiguration(rng);
                        int idSC = api.createSolverConfig(parameters.getIdExperiment(), paramConfig, "Random configuration");
                        SolverConfiguration solverConfig = new SolverConfiguration(idSC, paramConfig, parameters.getStatistics());
                        randomConfigs.add(solverConfig);
                        generatedConfigs.add(solverConfig);
                        initialDesignConfigs.add(solverConfig);
                    }
                    pacc.log("c Adding " + num + " random configuration to the initial design.");
                    return randomConfigs;
                }
                return new LinkedList<SolverConfiguration>(); // nothing to learn from (wait for initial design)
            }
            
            // Get the currently best configuration from the racing method
            List<SolverConfiguration> bestConfigs = new ArrayList<SolverConfiguration>();
            bestConfigs.addAll(pacc.racing.getBestSolverConfigurations(num));
            if (bestConfigs.isEmpty()) {
                for (SolverConfiguration sc: generatedConfigs) {
                    if (sc.isFinished()) bestConfigs.add(sc);
                }
            }
            if (bestConfigs.size() == 0) {
                pacc.log("c We don't have any best configuration yet. Wait for racing method to give us some");
                return new LinkedList<SolverConfiguration>(); 
            }
            Collections.sort(bestConfigs);
            Collections.reverse(bestConfigs);
            
            long start = System.currentTimeMillis();
            updateModel();
            pacc.log("c Learning the model from " + generatedConfigs.size() + " configs and " + numFinishedJobs + " runs in total took " + (System.currentTimeMillis() - start) + " ms");
            
            double f_min = bestConfigs.get(0).getCost();
            if (logModel) f_min = Math.log10(f_min);
            //double[][] inc_theta_pred = model.predict(new double[][] {paramConfigToTuple(bestConfigs.get(0).getParameterConfiguration())});
            //f_min = inc_theta_pred[0][0];
            pacc.log("c Current best configuration: " + bestConfigs.get(0).getIdSolverConfiguration() + " " + bestConfigs.get(0).getParameterConfiguration().toString() + " with cost " + bestConfigs.get(0).getCost());
            
            // Select new configurations
            for (ParameterConfiguration paramConfig: selectConfigurations(numConfigsToGenerate, f_min)) {
                if (api.exists(parameters.getIdExperiment(), paramConfig) != 0) {
                    pacc.log("c WARNING selected configuration already exists. Skipping");
                    int idSC = api.exists(parameters.getIdExperiment(), paramConfig);
                    ParameterConfiguration existing = api.getParameterConfiguration(parameters.getIdExperiment(), idSC);
                    pacc.log("c Existing config: " + existing);
                    pacc.log("c Selected config: " + paramConfig);
                    pacc.log("c " + paramConfig.hashCode() + " " + existing.hashCode());
                    pacc.log("c allSelectedConfigs contains " + allSelectedConfigs.contains(paramConfig));
                    pacc.log("c equals " + paramConfig.equals(existing));
                    pacc.log("c key sets equal " + paramConfig.getParameter_instances().keySet().equals(existing.getParameter_instances().keySet()));
                    continue;
                }
                int idSC = api.createSolverConfig(parameters.getIdExperiment(), paramConfig, api.getCanonicalName(parameters.getIdExperiment(), paramConfig));
                if (createIBSConfigs) {
                    newConfigs.add(createIBSConfig(idSC, paramConfig));
                } else {
                    newConfigs.add(new SolverConfiguration(idSC, paramConfig, parameters.getStatistics()));
                }
            }

            pacc.log("Adding " + newConfigs.size() + " configurations to the queue");
            Collections.shuffle(newConfigs, rng);
            generatedConfigs.addAll(newConfigs);
            configurationQueue.addAll(newConfigs);
        }
        
        pacc.log("c Racing requested " + num + " configurations. " + configurationQueue.size() + " queued.");
        int curQueued = configurationQueue.size();
        List<SolverConfiguration> retConfigs = new LinkedList<SolverConfiguration>();
        for (int i = 0; i < Math.min(curQueued, num); i++) {
            retConfigs.add(configurationQueue.remove(0));
        }

        return retConfigs;
    }
    
    private ThetaPrediction[] getThetaPredictions(List<ParameterConfiguration> configs) {
        double[][] thetas = new double[configs.size()][];
        ThetaPrediction[] thetaPred = new ThetaPrediction[configs.size()];
        int ix = 0;
        for (ParameterConfiguration config: configs) {
            thetas[ix] = paramConfigToTuple(config);
            thetaPred[ix] = new ThetaPrediction();
            thetaPred[ix].paramConfig = config;
            thetaPred[ix].theta = thetas[ix];
            ix++;
        }
        double[][] preds = model.predict(thetas);
        for (int i = 0; i < configs.size(); i++) {
            thetaPred[i].mu = preds[i][0];
            thetaPred[i].sigma = Math.sqrt(preds[i][1]);
        }
        return thetaPred;
    }

    private List<ParameterConfiguration> selectConfigurations(int numConfigsToGenerate, final double f_min) throws Exception {
        ExponentialDistribution expDist = new ExponentialDistribution(ocbExpMu);
        final double[] ocb_lambda = expDist.sample(numConfigsToGenerate);
        
        // Get predictions for the current configurations
        long start = System.currentTimeMillis();
        List<ParameterConfiguration> listGeneratedConfigs = new ArrayList<ParameterConfiguration>(generatedConfigs.size());
        for (SolverConfiguration config: generatedConfigs) listGeneratedConfigs.add(config.getParameterConfiguration());
        final ThetaPrediction[] generatedThetaPred = getThetaPredictions(listGeneratedConfigs); 
        pacc.log("c Predicting " + generatedConfigs.size() + " current configurations took " + (System.currentTimeMillis() - start) + " ms");
        
        // Get predictions for random configurations
        start = System.currentTimeMillis();
        // Generate random configurations in parallel
        final List<ParameterConfiguration> randomConfigurations = Collections.synchronizedList(new LinkedList<ParameterConfiguration>());
        final int availProcs = numProcs;
        pacc.log("Generating " + numRandomTheta + " random configurations using " + availProcs + " processors.");
        ExecutorService exec = Executors.newFixedThreadPool(availProcs);
        try {
            for (int chunk = 0; chunk < availProcs; chunk++) {
                exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int u = 0; u < (numRandomTheta / availProcs); u++) {
                                ParameterConfiguration randomConfig = canUseFastMethods ? pspace.getRandomConfigurationFast(rng) : pspace.getRandomConfiguration(rng);
                                randomConfigurations.add(randomConfig);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        } finally {
            exec.shutdown();
        }
        exec.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

        pacc.log("c Generating " + randomConfigurations.size() + " random configurations took " + (System.currentTimeMillis() - start) + " ms");
        start = System.currentTimeMillis();
        final ThetaPrediction[] randomThetaPred = getThetaPredictions(randomConfigurations);
        pacc.log("c Predicting " + numRandomTheta + " random configurations took " + (System.currentTimeMillis() - start) + " ms");
        
        // Optimize criteria
        start = System.currentTimeMillis();
        final List<ParameterConfiguration> selectedConfigs = new LinkedList<ParameterConfiguration>();
        if ("ocb".equals(selectionCriterion)) {
            ExecutorService execOptimize = Executors.newFixedThreadPool(numProcs);
            try {
                // Optimize ocb for different lambdas in parallel
                for (int o = 0; o < numConfigsToGenerate; o++) {
                    final int j = o;
                    final String threadInfo = "[ix: " + j + ", lambda: " + ocb_lambda[j] + "]";
                    
                    execOptimize.submit(new Runnable() {
                    @Override
                    public void run() {
                        ThetaCrit[] randomThetaCrit = new ThetaCrit[numRandomTheta];
                        double bestRandomValue = Double.NEGATIVE_INFINITY;
                        for (int i = 0; i < numRandomTheta; i++) {
                            randomThetaCrit[i] = new ThetaCrit();
                            randomThetaCrit[i].pred = randomThetaPred[i];
                            randomThetaCrit[i].value = -randomThetaPred[i].mu + ocb_lambda[j] * randomThetaPred[i].sigma;
                            if (randomThetaCrit[i].value > bestRandomValue) bestRandomValue = randomThetaCrit[i].value;
                        }
                        

                        ThetaCrit[] thetaCrit = new ThetaCrit[generatedThetaPred.length];
                        for (int i = 0; i < generatedThetaPred.length; i++) {
                            thetaCrit[i] = new ThetaCrit();
                            thetaCrit[i].pred = generatedThetaPred[i];
                            thetaCrit[i].value = -generatedThetaPred[i].mu + ocb_lambda[j] * generatedThetaPred[i].sigma; 
                        }
                        Arrays.sort(thetaCrit);

                        final int numLS = numTopLS;
                        long lsStart = System.currentTimeMillis();
                        ThetaCrit[] allThetaCrit = new ThetaCrit[numRandomTheta + Math.min(numLS, generatedThetaPred.length)];
                        // Optimize the top-numLS configurations using local search
                        for (int i = 0; i < Math.min(numLS, generatedThetaPred.length); i++) {
                            ParameterConfiguration paramConfig = thetaCrit[i].pred.paramConfig;
                            //pacc.log("c "+threadInfo+" Starting local search from current configuration with ocb " + thetaCrit[i].value);
                            try {
                                paramConfig = optimizeLocally(paramConfig, thetaCrit[i].value, ocb_lambda[j], f_min);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            thetaCrit[i].pred = new ThetaPrediction();
                            thetaCrit[i].pred.theta = paramConfigToTuple(paramConfig);
                            thetaCrit[i].pred.paramConfig = paramConfig;
                            double[][] newPred = model.predict(new double[][] { thetaCrit[i].pred.theta });
                            thetaCrit[i].pred.mu = newPred[0][0];
                            thetaCrit[i].pred.sigma = Math.sqrt(newPred[0][1]);
                            thetaCrit[i].value = -thetaCrit[i].pred.mu + ocb_lambda[j] * thetaCrit[i].pred.sigma;
                            //pacc.log("c "+threadInfo+" LS optimized configuration to ocb " + thetaCrit[i].value);
                            allThetaCrit[i] = thetaCrit[i];
                        }
                        pacc.log("c "+threadInfo+" LS optimization took " + (System.currentTimeMillis() - lsStart) + " ms");
                        // Now combine the top numLS configurations with the random configurations
                        for (int i = 0; i < numRandomTheta; i++) {
                            allThetaCrit[Math.min(numLS, generatedThetaPred.length) + i] = randomThetaCrit[i];
                        }
        
                        // Sort again
                        Arrays.sort(allThetaCrit);
        
                        // and use one of the final best ones for this ocb_lambda value
                        int numBest = 1;
                        double valBest = allThetaCrit[0].value;
                        while (numBest < allThetaCrit.length && allThetaCrit[numBest].value == valBest) {
                           numBest++;
                        }
                        
                        pacc.log("c "+threadInfo+" OCB maximization found " + numBest + " configurations with same ocb. Choosing top 3 starting from randomly chosen best.");
                        int numChosen = 0;
                        int ix = rng.nextInt(numBest);
                        for (int i = ix; i < allThetaCrit.length && numChosen < numTopSel; i++) {
                            ThetaCrit selectedThetaCrit = allThetaCrit[i];
                            ParameterConfiguration paramConfig = selectedThetaCrit.pred.paramConfig;
                            paramConfig.updateChecksum();
                            synchronized (selectedConfigs) {
                                if (allSelectedConfigs.contains(paramConfig)) continue;
                                selectedConfigs.add(paramConfig);
                                allSelectedConfigs.add(paramConfig);
                            }
                            statTotalOptimizations++;
                            numChosen++;
                            if (selectedThetaCrit.value == bestRandomValue) {
                                statNumBestRandom++;
                            }
                            //pacc.log("c "+threadInfo+" OCB maximization selected configuration with ocb " + selectedThetaCrit.value + " -- Configuration: " + selectedThetaCrit.pred.paramConfig);
                        }
                    }
                    });
                }
            } finally {
                execOptimize.shutdown();
            }
            execOptimize.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } else {
            // EI
            throw new RuntimeException("EI not implemented yet");
        }
        
        pacc.log("c Optimizing ocb criteria to select " + numConfigsToGenerate +  " configurations took " + (System.currentTimeMillis() - start) + " ms");
        return selectedConfigs;
    }
    
    private SolverConfiguration createIBSConfig(int idSC, ParameterConfiguration paramConfig) {
        double[][] theta_config = new double[][] { paramConfigToTuple(paramConfig) };
        Set<Integer> preferredInstanceIDs = new HashSet<Integer>();
        Map<Integer, Double> meanByInstanceID = new HashMap<Integer, Double>();
        for (Instance instance: instances) {
            int[] inst_ix = new int[] { instanceFeaturesIx.get(instance.getId()) };
            double[][] pred = model.predictMarginal(theta_config, inst_ix);
            double predicted_mean = pred[0][0];
            double predicted_var = pred[0][1];
            meanByInstanceID.put(instance.getId(), predicted_mean);
        }
        Map<Integer, Double> bestByInstanceID = new HashMap<Integer, Double>();
        for (SolverConfiguration sc : pacc.racing.getBestSolverConfigurations()) {
            Map<Integer, List<ExperimentResult>> results = new HashMap<Integer, List<ExperimentResult>>();
            for (ExperimentResult er : sc.getJobs()) {
                List<ExperimentResult> list = results.get(er.getInstanceId());
                if (list == null) {
                    list = new LinkedList<ExperimentResult>();
                    results.put(er.getInstanceId(), list);
                }
                list.add(er);
            }
            
            for (Instance instance: instances) {
                List<ExperimentResult> list = results.get(instance.getId());
                if (list != null) {
                    double cost = par1CostFunc.calculateCost(list);
                    Double best = bestByInstanceID.get(instance.getId());
                    if (best == null || best < cost) {
                        bestByInstanceID.put(instance.getId(), cost);
                    } 
                }
            }
        }
        
        for (Instance instance : instances) {
            Double best = bestByInstanceID.get(instance.getId());
            Double cost = meanByInstanceID.get(instance.getId());
            if (best == null || cost * .9 < best) {
                preferredInstanceIDs.add(instance.getId());
            }
        }
        pacc.log("[SMBO] Generated an IBS configuration with " + preferredInstanceIDs.size() + " preferred instances.");
        return new SolverConfigurationIBS(idSC, paramConfig, parameters.getStatistics(), preferredInstanceIDs);
    }
    
    private ParameterConfiguration optimizeLocally(ParameterConfiguration paramConfig, double startCriterionValue, double ocb_lambda, double f_min) throws Exception {
        ParameterConfiguration incumbent = paramConfig;
        int localSearchSteps = 0;
        final double eps = 1e-5;
        double incCriterionValue = startCriterionValue;
        while (localSearchSteps++ < maxLocalSearchSteps) {
            List<ParameterConfiguration> nbrs = canUseFastMethods ? pspace.getGaussianNeighbourhoodFast(incumbent, rng, lsStddev, lsSamples, true) : pspace.getGaussianNeighbourhood(incumbent, rng, lsStddev, lsSamples, true);
            Collections.shuffle(nbrs, rng);
            double[][] nbrsTheta = new double[nbrs.size()][];
            for (int i = 0; i < nbrs.size(); i++) nbrsTheta[i] = paramConfigToTuple(nbrs.get(i));
            double[][] nbrsThetaPred = model.predict(nbrsTheta);
            
            int bestIx = -1;
            double bestIxValue = incCriterionValue;

            for (int i = 0; i < nbrs.size(); i++) {
                double sigma = Math.sqrt(nbrsThetaPred[i][1]);
                double mu = nbrsThetaPred[i][0];
                
                double criterionValue;
                if ("ocb".equals(selectionCriterion)) {
                    criterionValue = -mu + ocb_lambda * sigma;
                } else {
                    criterionValue = calcExpectedImprovement(mu, sigma, f_min);
                }
                
                if (criterionValue > bestIxValue + eps) {
                    // check if this neighbour significantly (more than eps) improves the criterion
                    bestIx = i;
                    bestIxValue = criterionValue;
                    incCriterionValue = criterionValue;
                }
            }
            
            if (bestIx == -1) return incumbent; // probably local optimum 
            
            incumbent = nbrs.get(bestIx);
        }
        return incumbent;
    }
    
    private void updateModel() throws Exception {
        double[][] theta = new double[generatedConfigs.size()][];
        Map<SolverConfiguration, Integer> solverConfigTheta = new HashMap<SolverConfiguration, Integer>();
        int countJobs = 0;
        int cIx = 0;
        for (SolverConfiguration config: generatedConfigs) {
            solverConfigTheta.put(config, cIx);
            theta[cIx] = paramConfigToTuple(config.getParameterConfiguration());
            countJobs += config.getNumFinishedJobs();
            cIx++;
        }

        int[][] theta_inst_idxs = new int[countJobs][2];
        boolean[] censored = new boolean[countJobs];
        double[] y = new double[countJobs];
        
        double maxy = Double.NEGATIVE_INFINITY;
        
        int jIx = 0;
        for (SolverConfiguration config: generatedConfigs) {
            for (ExperimentResult run: config.getFinishedJobs()) {
                theta_inst_idxs[jIx][0] = solverConfigTheta.get(config);
                theta_inst_idxs[jIx][1] = instanceFeaturesIx.get(run.getInstanceId());
                censored[jIx] = !run.getResultCode().isCorrect();
                y[jIx] = par1CostFunc.singleCost(run);
                if (logModel) {
                    if (y[jIx] <= 0) {
                        pacc.log_db("Warning: logarithmic model used with values <= 0. Pruning to 1e-15.");
                        pacc.log("Warning: logarithmic model used with values <= 0. Pruning to 1e-15.");
                        y[jIx] = 1e-15;
                    }
                    y[jIx] = Math.log10(y[jIx]);
                }
                if (y[jIx] > maxy) maxy = y[jIx];
                jIx++;
            }
        }

        model.learnModel(theta, instanceFeatures, configurableParameters.size(), instanceFeatureNames.size(), theta_inst_idxs, y, censored);
    }
    
    private void parseSMBOParameters() {
        String val;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_samplingPath")) != null)
            samplingPath = val;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numPC")) != null)
            numPC = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_logModel")) != null)
            logModel = Integer.valueOf(val) == 1;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_selectionCriterion")) != null)
            selectionCriterion = val;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numInitialConfigurationsFactor")) != null)
            numInitialConfigurationsFactor = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numRandomTheta")) != null)
            numRandomTheta = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_maxLocalSearchSteps")) != null)
            maxLocalSearchSteps = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_lsStddev")) != null)
            lsStddev = Float.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_lsSamples")) != null)
            lsSamples = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_nTrees")) != null)
            nTrees = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_ocbExpMu")) != null)
            ocbExpMu = Float.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_EIg")) != null)
            EIg = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_queueSize")) != null)
            queueSize = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_createIBSConfigs")) != null)
            createIBSConfigs = Boolean.parseBoolean(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_featureFolder")) != null)
            featureFolder = val;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_featureCacheFolder")) != null)
            featureCacheFolder = val;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_initialDesignFromDefault")) != null)
            initialDesignFromDefault = Integer.valueOf(val) == 1;
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numTopLS")) != null)
            numTopLS = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numTopSel")) != null)
            numTopSel = Integer.valueOf(val);
        if ((val = parameters.getSearchMethodParameters().get("SMBO_numProcs")) != null)
            numProcs = Integer.valueOf(val);
    }

    @Override
    public List<String> getParameters() {
        List<String> p = new LinkedList<String>();
        p.add("% --- SMBO parameters ---");
        p.add("SMBO_samplingPath = <REQUIRED> % (Path to the external sequence generating program)");
        p.add("SMBO_numPC = "+this.numPC+ " % (How many principal components of the instance features to use)");
        p.add("SMBO_selectionCriterion = "+this.selectionCriterion+ " % (Improvement criterion {ocb, ei, eEI})");
        p.add("SMBO_numInitialConfigurationsFactor = "+this.numInitialConfigurationsFactor+ " % (How many configurations to sample randomly for the initial model)");
        p.add("SMBO_numRandomTheta = "+this.numRandomTheta+ " % (How many random configurations should be predicted for criterion optimization)");
        p.add("SMBO_maxLocalSearchSteps = "+this.maxLocalSearchSteps+ " % (Up to how many steps should each configuration be optimized by LS with the model)");
        p.add("SMBO_lsStddev = "+this.lsStddev+ " % (Standard deviation to use in LS neighbourhood sampling)");
        p.add("SMBO_lsSamples = "+this.lsSamples+ " % (How many samples per parameter in the LS neighbourhood sampling)");
        p.add("SMBO_nTrees = "+this.nTrees+ " % (The number of regression trees in the random forest)");
        p.add("SMBO_ocbExpMu = "+this.ocbExpMu+ " % (mean value of the exponential distribution from which the lambda values are sampled, only applies to ocb selectionCriterion)");
        p.add("SMBO_EIg = "+this.EIg+ " % (global search parameter g in the expected improvement criterion, integer in {1,2,3}, 1=original criterion, 3=more global search behaviour)");
        p.add("SMBO_queueSize = "+this.queueSize+ " % (How many configurations to generate at a time using SMBO. Also the maxmimum number of configurations returned to racing at a time.)");
        p.add("SMBO_createIBSConfigs = "+this.createIBSConfigs+ " % (Create IBS configs)");
        p.add("SMBO_featureFolder = "+this.featureFolder == null ? "n/a" : this.featureFolder+ " % (Instance features folder)");
        p.add("SMBO_featureCacheFolder = "+this.featureCacheFolder == null ? "n/a" : this.featureCacheFolder+ " % (Instance features cache folder)");
        p.add("SMBO_initialDesignFromDefault = "+this.initialDesignFromDefault+ " % (Create a initial design by evaluating the neighbourhoods of the default configurations)");
        p.add("SMBO_numTopLS = "+this.numTopLS+ " % (How many local search optimisations should be started)");
        p.add("SMBO_numTopSel = "+this.numTopSel+ " % (How many configurations with best criterion value to choose)");
        p.add("SMBO_numProcs = "+this.numProcs+ " % (Number of processor EDACC-MBO can use on the machine for parallelisation)");
        p.add("% -----------------------\n");
        return p;
    }

    @Override
    public void searchFinished() {
        pacc.log("c Out of " + statTotalOptimizations + " criterion optimizations, " + statNumBestRandom + " where due to a random config");
        /*pacc.log("c Calculating variable importance measures from OOB samples:");
        double[] VI = model.calculateVI();
        for (int i = 0; i < configurableParameters.size(); i++) {
            pacc.log(configurableParameters.get(i).getName() + ": " + VI[i]);
        }
        for (int i = 0; i < instanceFeatureNames.size(); i++) {
            pacc.log(instanceFeatureNames.get(i) + ": " + VI[configurableParameters.size() + i]);
        }*/
    }
    
    double calcExpectedImprovement(double mu, double sigma, double f_min) throws MathException {
        if ("eEI".equals(selectionCriterion)) return expExpectedImprovement(mu, sigma, f_min);
        else return expectedImprovement(mu, sigma, f_min);
    }

    private double expExpectedImprovement(double mu, double sigma, double f_min) {
        f_min = Math.log(10) * f_min;
        mu = Math.log(10) * mu;
        sigma = Math.log(10) * sigma;

        return Math.exp(f_min + Gaussian.normcdfln((f_min - mu) / sigma))
                - Math.exp(sigma * sigma / 2.0 + mu + Gaussian.normcdfln((f_min - mu) / sigma - sigma));
    }

    private double expectedImprovement(double mu, double sigma, double f_min) throws MathException {
        double x = (f_min - mu) / sigma;
        double ei;
        if (EIg == 1) ei = (f_min - mu) * Gaussian.Phi(x) + sigma * Gaussian.phi(x);
        else if (EIg == 2) ei = sigma*sigma * ((x*x + 1) * Gaussian.Phi(x) + x * Gaussian.phi(x));
        else if (EIg == 3) ei = sigma*sigma*sigma * ((x*x*x + 3*x) * Gaussian.Phi(x) + (2 + x*x) * Gaussian.phi(x));
        else ei = 0;
        
        return ei;
    }
    
    private double[] paramConfigToTuple(ParameterConfiguration paramConfig) {
        double[] theta = new double[configurableParameters.size()];
        for (Parameter p: configurableParameters) {
            int pIx = configurableParameters.indexOf(p);
            Object paramValue = paramConfig.getParameterValue(p);
            if (paramValue == null) theta[pIx] = Double.NaN;
            else {
                if (p.getDomain() instanceof RealDomain) {
                    if (paramValue instanceof Float) {
                        theta[pIx] = (Float)paramValue;
                    } else if (paramValue instanceof Double) {
                        theta[pIx] = (Double)paramValue;
                    }
                } else if (p.getDomain() instanceof IntegerDomain) {
                    if (paramValue instanceof Integer) {
                        theta[pIx] = (Integer)paramValue;
                    } else if (paramValue instanceof Long) {
                        theta[pIx] = (Long)paramValue;
                    }
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
                    if (FlagDomain.FLAGS.ON.equals(paramValue)) theta[pIx] = 2;
                    else theta[pIx] = 1;
                } else {
                    // TODO
                    theta[pIx] = paramValue.hashCode();
                    throw new RuntimeException("Domain " + p.getDomain().getName() + " not implemented yet.");
                }
            }
            
        }
        
        return theta;
    }
    
    /**
     * Map a real tuple to a parameter configuration. Not the inverse of paramConfigToTuple !!
     * @param values
     * @return
     */
    private ParameterConfiguration mapRealTupleToParameters(double[] values) {
        ParameterConfiguration pc = pspace.getRandomConfiguration(rng);
        int i = 0;
        for (Parameter p: configurableParameters) {
            if (pc.getParameterValue(p) == null) continue;
            double v = values[i++];
            if (p.getDomain() instanceof RealDomain) {
                RealDomain dom = (RealDomain)p.getDomain();
                pc.setParameterValue(p, dom.getLow() + v * (dom.getHigh() - dom.getLow()));
            } else if (p.getDomain() instanceof IntegerDomain) {
                IntegerDomain dom = (IntegerDomain)p.getDomain();
                pc.setParameterValue(p, Math.round(dom.getLow() + v * (dom.getHigh() - dom.getLow())));
            } else if (p.getDomain() instanceof CategoricalDomain) {
                CategoricalDomain dom = (CategoricalDomain)p.getDomain();
                List<String> categories = new LinkedList<String>(dom.getCategories());
                Collections.sort(categories);
                int ix = (int) (v * categories.size());
                if (ix == categories.size()) ix = 0;
                pc.setParameterValue(p, categories.get(ix));
            } else if (p.getDomain() instanceof OrdinalDomain) {
                OrdinalDomain dom = (OrdinalDomain)p.getDomain();
                int ix = (int) (v * dom.getOrdered_list().size());
                if (ix == dom.getOrdered_list().size()) ix = 0;
                pc.setParameterValue(p, dom.getOrdered_list().get(ix));
            } else if (p.getDomain() instanceof FlagDomain) {
                if (v < 0.5) {
                    pc.setParameterValue(p, FlagDomain.FLAGS.ON);
                } else {
                    pc.setParameterValue(p, FlagDomain.FLAGS.OFF);
                }
            }
        }
        return pc;
    }
    
    class ThetaCrit implements Comparable<ThetaCrit> {
        ThetaPrediction pred;
        double value;
        
        @Override
        public int compareTo(ThetaCrit o) {
            return -Double.compare(this.value, o.value);
        }
    }
    
    class ThetaPrediction {
        ParameterConfiguration paramConfig;
        double[] theta;
        double mu, sigma;
    }
}

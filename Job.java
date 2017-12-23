import javafx.util.Pair;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Oliver on 18/11/2017.
 * Written by Oliver Bathurst <oliverbathurst12345@gmail.com>
 */

class Job {
    private ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ArrayList<Mapper> mappers = new ArrayList<>();
    private final ArrayList<Reducer> reducers = new ArrayList<>();
    private final ArrayList<Parser> parsers = new ArrayList<>();
    private final ArrayList<Combiner> combiners = new ArrayList<>();
    private final Logger logger = new Logger();
    private final Config config;
    private long startTime;

    Job(Config conf){
        this.config = conf;
    }

    /**
     * Takes the input path from the config and runs the parser, with a chunk size (defaults to 128)
     */
    private void parse(){
        logger.log("Running Parser...");
        for(String filepath: config.getInputPaths()) { //get input files
            parsers.add(new Parser(filepath, config.getChunkSize()));
        }
        if(config.getMultiThreaded()){
            setupThreadPool();
            for (Parser p : parsers) {
                service.execute(p::run); //run the parsers
            }
            shutdownThreadPool();
        }else{
            for (Parser p : parsers) {
                p.run(); //run the parsers
            }
        }
    }
    @SuppressWarnings("unchecked")
    private void assignChunksToMappers(){
        if (config.getMapper() != null) {
            try {
                Constructor<?> cons = config.getMapper().getConstructor(String.class, Context.class);
                logger.log("Found map method...");
                for(Parser parser: parsers) {
                    for (ArrayList<String> chunk : parser.returnChunks()) {
                        mappers.add(new Mapper(chunk, new Context(), cons)); //give each chunk to a different mapper with a new context
                    }
                }
                logger.log("Mappers: " + mappers.size());
            }catch(Exception e) {
                logger.logCritical("Other error: " + e.getMessage() + " cause: " + e.getCause());
            }
        } else {
            logger.log("Mapper method 'mapper' not defined\n" + "use config.setMapper(class);");
        }
    }
    /**
     * runs each map in order
     */
    private void runMappers(){
        if(config.getMultiThreaded()) {
            setupThreadPool();
            for (Mapper map : mappers) {
                service.execute(map::run);
            }
            shutdownThreadPool();
        }else{
            for(Mapper map: mappers){
                map.run();
            }
        }
    }
    private void combine(){
        Combine grouper = new Combine(mappers, combiners);
        grouper.combine();
    }
    /**
     * "The process of bringing intermediate output to a set
     * of Reducers is known as the ‘shuffling’ process"
     */
    private void partition(){
        for(Mapper mapper: mappers){
            reducers.add(new Reducer(mapper.getIntermediateOutput()));
        }
    }
    /**
     * runs each reducer in order
     */
    private void runReducers(){
        if(config.getMultiThreaded()) {
            setupThreadPool();
            for (Reducer r : reducers) {
                service.execute(r::reduce);
            }
            shutdownThreadPool();
        }else{
            for(Reducer r: reducers){
                r.reduce();
            }
        }
    }
    private ArrayList<Pair<Object, Object>> getFinalKeyPairs(){
        FinalKeyPairs fkp = new FinalKeyPairs(config, new Merger(reducers).returnFinalPairs());
        fkp.run();
        return fkp.getFinalOutput();
    }
    /**
     * This is the output, writes every key/value pair to file, this is the final step
     */
    @SuppressWarnings("unchecked")
    private void output(){
        try {
            logger.log("Writing to file...");
            FileWriter fw = new FileWriter(new File(config.getOutputPath())); //get the stored output path

            ArrayList<Pair<Object, Object>> finalKeyValuePairs = getFinalKeyPairs();
            logger.log("Reduced set size: " + finalKeyValuePairs.size());
            for (Pair<Object, Object> objectEntry : finalKeyValuePairs) {
                fw.write("Key: " + objectEntry.getKey() + " Value: " + objectEntry.getValue() + "\n");
            }
            fw.flush();
            fw.close();
        }catch(Exception e){
            logger.logCritical("Cause: " + e.getCause() + " Message: " + e.getMessage());
        }
    }
    /**
     * This is the driver, performs all actions in order
     */
    void runJob(){
        setStartTime(System.currentTimeMillis());

        parse(); //parse input data into chunks
        assignChunksToMappers(); //push everything into mapper array
        runMappers(); //run all mappers in mapper array
        combine();
        partition();//assign mappers IO to reducers
        runReducers();
        output();

        logger.log("Completed Job: " + "'" + config.getJobName() + "'" + " to "
                + config.getOutputPath() + " in " + (System.currentTimeMillis() - getStartTime())
                + "ms");
    }
    private void setupThreadPool(){
        if(service.isShutdown()){
            service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        }
    }
    private void shutdownThreadPool() {
        service.shutdown();
        try {
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            logger.log(e.getMessage());
        }
    }
    private void setStartTime(long l){
        startTime = l;
    }
    private long getStartTime(){
        return startTime;
    }
}
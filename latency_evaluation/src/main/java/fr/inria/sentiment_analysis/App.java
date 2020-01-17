package fr.inria.sentiment_analysis;

import fr.inria.sentiment_analysis.data.Tweet;
import fr.inria.sentiment_analysis.nlp.NegativeWords;
import fr.inria.sentiment_analysis.nlp.PositiveWords;
import fr.inria.sentiment_analysis.nlp.StopWords;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.ExecutionMode;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.IngestionTimeExtractor;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer010;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;

import org.apache.flink.util.Collector;

import com.google.gson.GsonBuilder;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.*;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private final Properties props;
    private String kafkaTopic;
    private String kafkaSinkTopic;
    private int edgeOperators;
    private String fileResult;

    public static void main( String[] args) throws Exception{
        if (args.length != 3 ) {
            logger.error("Missing argumentos: (1)properties file, (2)result file, and (3)Operators in Edge");
        }
        Integer operatorsEdge = Integer.valueOf(args[2]);
        App app = new App(args[0], args[1], operatorsEdge);
        app.run();
    }

    public App(Reader propsReader, String result_path, int operatorsEdge) throws Exception {
        props = new Properties();
        props.load(propsReader);
        kafkaTopic = props.getProperty("kafka.topic");
        kafkaSinkTopic = props.getProperty("kafka.sink.topic");
        edgeOperators =  operatorsEdge;
        fileResult = result_path;

        logger.debug("Kafka topic: " + kafkaTopic + ", Edge operators: " + edgeOperators);
    }

    public App(String propsFilePath, String result_path, int operatorsEdge) throws Exception {
        this(Files.newBufferedReader(new File(propsFilePath).toPath()), result_path, operatorsEdge);
    }

    private Properties createKafkaConfig() {
        Properties kafkaConfig = new Properties();
        for (Object key : props.keySet()) {
            String keyStr = (String)key;
            /* Kafka properties in the config file start with "kafka.", but it needs
               to be removed before adding the propoerty values to kafkaConfig */
            if (keyStr.startsWith("kafka")) {
                kafkaConfig.put(keyStr.substring("kafka".length() + 1), props.get(key));
            }
        }
        return kafkaConfig;
    }

    public void run() throws Exception {
        logger.info("Creating Flink environment...");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        logger.info("Creating Kafka consumer...");
        FlinkKafkaConsumer010 consumer = new FlinkKafkaConsumer010<>(kafkaTopic,
                new SimpleStringSchema(),
                createKafkaConfig());

        logger.info("Creating Kafka source...");
        DataStream<String> streamKafka = env
                .addSource(consumer)
                .assignTimestampsAndWatermarks(new IngestionTimeExtractor());

        logger.info("Creating Kafka producer...");
        FlinkKafkaProducer010<String> producer = new FlinkKafkaProducer010<String>(
                createKafkaConfig().getProperty("bootstrap.servers"),            // broker list
                kafkaSinkTopic,                  // target topic
                new SimpleStringSchema());   // serialization schema


        Gson gson = new GsonBuilder().create();


        logger.info("Initialising operators...");
        LinkedList<DataStream<Tweet>> streams = new LinkedList<>();


        if(edgeOperators > -1 && edgeOperators < 5){
            streams.add(streamKafka.flatMap(new ConvertStringToTweet()));

        }

        if (edgeOperators == 0) {
            streams.add(streams.getLast().map( new FilterByLanguage("en")));
        }

        if (edgeOperators <= 1) {
            //put the text at lowercase
            try {
                streams.add(streams.getLast().map(new FilterText(s -> s.replaceAll("[^a-zA-Z\\s]", "").trim().toLowerCase())));
            }catch (Exception e){
                streams.clear();
            }
        }

        if (edgeOperators <= 2) {
            streams.add(streams.getLast().map(new FilterText(s -> {
                List<String> stopWords = StopWords.getWords();
                for (String word : stopWords) {
                    s = s.replaceAll("\\b" + word + "\\b", "");
                }
                return s;
            })));
        }

        if (edgeOperators <= 3) {
            streams.add(streams.getLast().map(new CountPositiveNegativeWords()));
        }

        if (edgeOperators <= 4) {
            streams.add(streams.getLast().map(t -> {
                t.setScore(t.getPositive() >= t.getNegative() ? "positive" : "negative");
                return t;
            }));

        }

        DataStream<String> tweetsAsJSONs = streams.getLast().flatMap(new ConvertTweetToJson());
        tweetsAsJSONs.addSink(producer);

//        DataStream<Tuple2<Long, String>> resultDS = tweetsAsJSONs.flatMap(new IncludeTimeStamp());
//        resultDS.print();

//        resultDS.writeAsCsv(this.fileResult);

        env.execute("SentimentAnalysis");
    }


    class ConvertTweetToJson implements FlatMapFunction<Tweet, String>{
        @Override
        public void flatMap(Tweet tweet, Collector<String> collector) throws Exception {
            try {
                Gson gson = new Gson();
                String stweet = gson.toJson(tweet, Tweet.class);
                collector.collect(stweet);
            } catch (Exception e) {
                collector.collect(null);
            }
        }
    }

    class ConvertStringToTweet implements FlatMapFunction<String, Tweet>{

        @Override
        public void flatMap(String value, Collector<Tweet> out) throws Exception {
            try {
                Gson gson = new Gson();
                Tweet tweet = gson.fromJson(value, Tweet.class);

                out.collect(tweet);

            } catch (Exception e) {
                out.collect(null);
            }
        }
    }


    /**
     * Filter operator, filter by language
     */
    class FilterByLanguage implements MapFunction<Tweet, Tweet> {
        private String language;

        public FilterByLanguage(String language) {
            this.language = language;
        }

        @Override
        public Tweet map(Tweet tweet) throws Exception {
            try {
                if (tweet.getLang().equalsIgnoreCase(language)) {
                    return tweet;
                } else {
                    return null;
                }

            } catch (Exception e) {
                return null;
            }
        }
    }


    /**
     * Text filter operator
     */
    class FilterText implements MapFunction<Tweet, Tweet>
    {
        MapFunction<String, String> funcText;

        FilterText(MapFunction<String, String> func) {
            funcText = func;
        }

        @Override
        public Tweet map(Tweet tweet) throws Exception {
            try {
                tweet.setText(funcText.map(tweet.getText()));
                return tweet;
            }catch(Exception e) {
                return null;
            }
        }
    }

    /**
     * Count number of positive and negative words in the text of a tweet
     */
    class CountPositiveNegativeWords implements MapFunction<Tweet, Tweet> {

        public Tweet map(Tweet tweet) {
            int numPos = 0;
            int numNeg = 0;
            try {
                for (String word : tweet.getText().split(" ")) {
                    if (PositiveWords.getWords().contains(word)) {
                        numPos++;
                    } else if (NegativeWords.getWords().contains(word)) {
                        numNeg++;
                    }
                }
                tweet.setNegative(numNeg);
                tweet.setPositive(numPos);

                // Null'ify the text and user fields, so that they are not serialised when converted to a json
                tweet.setText(null);
                tweet.setUser(null);

                return tweet;
            } catch (Exception e) {
                return null;
            }
        }
    }

    class IncludeTimeStamp implements FlatMapFunction<String, Tuple2<Long, String>>{
        @Override
        public void flatMap(String s, Collector<Tuple2<Long, String>> out) throws Exception {
            out.collect(new Tuple2<>(System.currentTimeMillis(), s));
        }
    }

}

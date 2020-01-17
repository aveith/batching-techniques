package fr.inria.sentiment_analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.inria.sentiment_analysis.data.Tweet;
import fr.inria.sentiment_analysis.nlp.NegativeWords;
import fr.inria.sentiment_analysis.nlp.PositiveWords;
import fr.inria.sentiment_analysis.nlp.StopWords;
import fr.inria.sentiment_analysis.utils.Util;
import org.apache.edgent.connectors.kafka.KafkaProducer;
import org.apache.edgent.connectors.mqtt.MqttConfig;
import org.apache.edgent.connectors.mqtt.MqttStreams;
import org.apache.edgent.console.server.HttpServer;
import org.apache.edgent.function.Function;
import org.apache.edgent.providers.development.DevelopmentProvider;
import org.apache.edgent.providers.direct.DirectProvider;
import org.apache.edgent.topology.TStream;
import org.apache.edgent.topology.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private DirectProvider provider;
//    private DevelopmentProvider provider;
    private Topology topology;
    private final Properties props;
    private String mqttTopic;
    private String kafkaTopic;
    private int edgeOperators;
    private String fileResult;

    public static void main( String[] args ) throws Exception {

        if (args.length != 3 ) {
            logger.error("Missing argumentos: (1)properties file, (2)result file, and (3)Operators in Edge");
        }
        Integer operatorsEdge = Integer.valueOf(args[2]);
        App app = new App(args[0], args[1], operatorsEdge);
        app.run();
    }

    public App(Reader propsReader, String resutl_path, int operatorsEdge) throws Exception {
        props = new Properties();
        props.load(propsReader);
        mqttTopic = props.getProperty("mqtt.topic");
        kafkaTopic = props.getProperty("kafka.topic");

        edgeOperators =  operatorsEdge;
        fileResult = resutl_path;

        if (edgeOperators == 5){
            kafkaTopic = props.getProperty("kafka.sink.topic");
        }

        logger.debug("MQTT topic: " + mqttTopic + ", Kafka topic: " + kafkaTopic + ", Edge operators: " + edgeOperators);
    }

    public App(String propsFilePath, String resutl_path, int operatorsEdge) throws Exception {
        this(Files.newBufferedReader(new File(propsFilePath).toPath()), resutl_path, operatorsEdge);
    }

    private MqttConfig createMqttConfig() {
        MqttConfig mqttConfig = MqttConfig.fromProperties(props);
        return mqttConfig;
    }

    private Map<String,Object> createKafkaConfig() {
        Map<String,Object> kafkaConfig = new HashMap<>();
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
        provider = new DirectProvider();
//        provider = new DevelopmentProvider();
        topology = provider.newTopology(" sentimentAnalysisTopology");

        // Create the MQTT broker connector
        logger.info("Subscribing to MQTT...");
        MqttConfig mqttConfig = createMqttConfig();
        MqttStreams mqtt = new MqttStreams(topology, () -> mqttConfig);

        logger.info("Creating Kafka producer...");
        // Create the Kafka Producer broker connector
        Map<String,Object> kafkaConfig = createKafkaConfig();
        KafkaProducer kafka = new KafkaProducer(topology, () -> kafkaConfig);

        logger.info("Initialising MQTT queue...");
        // Subscribe to the topic and create a stream of messages
        TStream<String> msgs = mqtt.subscribe(mqttTopic, 0/*qos*/);

        logger.info("Initialising operators...");
        Gson gson = new GsonBuilder().create();

        if (edgeOperators!=0) {
            LinkedList<TStream<Tweet>> streams = new LinkedList<>();
            if (edgeOperators >= 1) {
                streams.add(msgs.map(new FilterByLanguage("en")));
            }

            if (edgeOperators >= 2) {
                streams.add(streams.getLast().map(new FilterText(s ->
                        s.replaceAll("[^a-zA-Z\\s]", "").trim().toLowerCase())));
            }

            if (edgeOperators >= 3) {
                streams.add(streams.getLast().map(new FilterText(s -> {
                    List<String> stopWords = StopWords.getWords();
                    for (String word : stopWords) {
                        s = s.replaceAll("\\b" + word + "\\b", "");
                    }
                    return s;
                })));
            }

            if (edgeOperators >= 4) {
                streams.add(streams.getLast().map(new CountPositiveNegativeWords()));
            }

            if (edgeOperators >= 5) {
                streams.add(streams.getLast().map(t -> {
                    t.setScore(t.getPositive() >= t.getNegative() ? "positive" : "negative");
                    return t;
                }));
            }

            TStream<String> tweetsAsJSONs = streams.getLast().map(tweet -> gson.toJson(tweet, Tweet.class));
//            tweetsAsJSONs.sink(tuple -> {
//                try {
//                    File logFile = new File(this.fileResult);
//
//                    // if file doesnt exists, then create it
//                    if (!logFile.exists()) {
//                        logFile.createNewFile();
//                    }
//
//                    // true = append file
//                    FileWriter fw = new FileWriter(logFile.getAbsoluteFile(), true);
//                    BufferedWriter bw = new BufferedWriter(fw);
//
//                    bw.write(String.format("%s;%s\n", String.valueOf(System.currentTimeMillis()), tuple));
//
//                    bw.close();
//                    fw.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            });

            kafka.publish(tweetsAsJSONs, kafkaTopic);
        }else {

//            msgs.sink(tuple -> {
//                try {
//                    File logFile = new File(this.fileResult);
//
//                    // if file doesnt exists, then create it
//                    if (!logFile.exists()) {
//                        logFile.createNewFile();
//                    }
//
//                    // true = append file
//                    FileWriter fw = new FileWriter(logFile.getAbsoluteFile(), true);
//                    BufferedWriter bw = new BufferedWriter(fw);
//
//                    bw.write(String.format("%s;%s\n", String.valueOf(System.currentTimeMillis()), tuple));
//
//                    bw.close();
//                    fw.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            });

            kafka.publish(msgs, kafkaTopic);
        }



//            System.out.println(String.format("%s -> received: %s", System.currentTimeMillis(), tuple));});

//        System.out.println(provider.getServices().getService(HttpServer.class).getConsoleUrl());
        provider.submit(topology);
    }

    /**
     * Filter operator, filter by language
     */
    class FilterByLanguage implements Function<String, Tweet> {
        private Gson gson = new Gson();
        private String language;

        FilterByLanguage(String language) {
            this.language = language;
        }

        public Tweet apply(String value) {
            try {
                Tweet tweet = gson.fromJson(value, Tweet.class);
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
    class FilterText implements Function<Tweet, Tweet> {
        Function<String, String> funcText;

        FilterText(Function<String, String> func) {
            funcText = func;
        }

        public Tweet apply(Tweet tweet) {
            try {
                tweet.setText(funcText.apply(tweet.getText()));
                return tweet;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Count number of positive and negative words in the text of a tweet
     */
    class CountPositiveNegativeWords implements Function<Tweet, Tweet> {

        public Tweet apply(Tweet tweet) {
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
}
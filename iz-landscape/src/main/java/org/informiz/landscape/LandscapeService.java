package org.informiz.landscape;

import static org.informiz.comm.Util.DEFAULT_QUEUE_HOST;
import static org.informiz.comm.Util.LANDSCAPE_QUEUE_NAME;
import static org.informiz.comm.Util.QUEUE_HOST_KEY;
import static org.informiz.comm.Util.createJsonErrorResp;
import static org.neo4j.helpers.collection.MapUtil.map;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.informiz.comm.LandscapeRequest;
import org.informiz.executor.CypherExecutor;
import org.informiz.executor.JdbcCypherExecutor;
import org.informiz.executor.QueryResultIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

/**
 * @author Nira Amit
 */
public class LandscapeService {

	static Logger logger = LoggerFactory.getLogger(LandscapeService.class);

	private static final String LANDSCAPE_QUERY = "MATCH (informi:Informi)-[r]->(other:Informi) "
	+ "WHERE informi.id={1} OR other.id={1} "
	+ "RETURN informi, r, other "
	+ "LIMIT {2}";

    private final CypherExecutor cypher;
    Connection connection = null;
    Channel channel = null;
    QueueingConsumer consumer = null;

    public LandscapeService(Properties props) throws Exception {
    	cypher = new JdbcCypherExecutor(props);
    	ConnectionFactory factory = new ConnectionFactory();
    	String hostname = props.getOrDefault(QUEUE_HOST_KEY, DEFAULT_QUEUE_HOST).toString();
		factory.setHost(hostname);

    	connection = factory.newConnection();
    	channel = connection.createChannel();

    	channel.queueDeclare(LANDSCAPE_QUEUE_NAME, false, false, false, null);

    	channel.basicQos(1);

    	consumer = new QueueingConsumer(channel);
		channel.basicConsume(LANDSCAPE_QUEUE_NAME, false, consumer);
    }

    public void process() {
    	Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		logger.info("Landscape service ready");
    	try {
    		while (true) {
    			Map<String, Object> response = null;
    			String asJson = null;

    			QueueingConsumer.Delivery delivery = consumer.nextDelivery();

    			BasicProperties props = delivery.getProperties();
    			BasicProperties replyProps = new BasicProperties
    					.Builder()
    					.correlationId(props.getCorrelationId())
    					.build();

    			try {
    				String message = new String(delivery.getBody(),"UTF-8");
    				LandscapeRequest req = gson.fromJson(message, LandscapeRequest.class);

    				response = graph(req.getInformiId(), req.getLimit());
    				asJson = gson.toJson(response);
    			}
    			catch (Exception e){
    				logger.error("Error while attempting to retrieve landscape: " + e.getMessage(), e);
    				asJson = createJsonErrorResp("Failed to retrieve landscape: " + e.getMessage());
    			}
    			finally {  
    				channel.basicPublish( "", props.getReplyTo(), replyProps, asJson.getBytes("UTF-8"));
    				channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
    			}
    		}
    	}
    	catch  (Exception e) {
    		e.printStackTrace();
    	}
    	finally {
    		close();
    	}      		      
    }

	public void close() {
		if (connection != null) {
			try {
				connection.close();
			}
			catch (Exception ignore) {}
		}
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> graph(int informiId, int limit) {
		try(QueryResultIterator result = cypher.query(
				LANDSCAPE_QUERY, map("1",informiId, "2",limit))) {
			List<Map<String, Object>> nodes = Lists.newArrayList();
			List<Map<String, Object>> rels = Lists.newArrayList();
			List<Integer> ids = new ArrayList<Integer>();

			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				Map<String, Object> source = (Map<String, Object>)row.get("informi");
				addNode(source, informiId, ids, nodes);
				Map<String, Object> target = (Map<String, Object>)row.get("other");
				addNode(target, informiId, ids, nodes);
				Map<String, Object> rel = (Map<String, Object>)row.get("r");
				rels.add(map("source", source.get("id"), "target", target.get("id"), 
						"caption", rel.get("description"), "type", "Relation"));
			}

			return map("graph", map("nodes", nodes, "edges", rels), "labels", Arrays.asList("Informi"), "errors", "");
		}
	}

	private void addNode(Map<String, Object> node, int rootId, List<Integer> ids, List<Map<String, Object>> nodes) {
		Map<String, Object> informi = Maps.newHashMap(node);
		Integer id = (Integer)informi.get("id");
		if (! ids.contains(id)) {
			ids.add(id);
			nodes.add(informi);
			informi.put("type", "Informi");
			if (id == rootId) {
				informi.put("root", true);
			}
		}
	}
	
	public static void main(String[] args) {
		LandscapeService service = null;
		try (FileInputStream in = new FileInputStream(args[0])) {
			Properties props = new Properties();
			props.load(in);
        	service = new LandscapeService(props);
			service.process();
		} catch (Exception e) {
			logger.error("Exception while trying to initialize landscape service: " + e.getMessage(), e);
			if (service != null) {
				service.close();
    		}
			System.exit(1);
		}
	}
}

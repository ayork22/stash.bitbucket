package stash;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class StashFPwrapper {

	static Logger logger = Logger.getLogger(StashFPwrapper.class);

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws ParseException {
		// ******Configure log4J*****
//		final Logger logger = Logger.getLogger(StashFPwrapper.class);
		{
			String log4jConfigFile = System.getProperty("user.dir") + File.separator + "stash.properties";
			PropertyConfigurator.configure(log4jConfigFile);
		}
		// Executer kicks of off run method @ set interval
		Runnable servers = new Runnable() {

			public void run() {

				String metricRootLocation = (GetPropertiesFile.getPropertyValue("MetricLocation"));
				// Add Rest API /repos to Stash URL provided in properties file
				String StashRepoUrl = GetPropertiesFile.getPropertyValue("HTTPorHTTPS") + "://" + GetPropertiesFile.getPropertyValue("StashURL") + "/rest/api/1.0/repos?limit=1000";
				logger.info("Stash Monitored URL = " + StashRepoUrl);
				logger.debug("MetricLocation  = " + metricRootLocation);
				
				// *****Create Metrics******
				// Array of actual Metrics WITHOUT the metrics KEY in front
				JSONArray metricArray = new JSONArray();

				// ******Get main WebService*******
				// Using Properties File to pass in 'callURL' parameters
				JSONObject mainWebServiceJSON = null;
				try {
					mainWebServiceJSON = (JSONObject) new JSONParser().parse(WebServiceHandler.callURL(StashRepoUrl,
							(GetPropertiesFile.getPropertyValue("StashUserName")),
							(GetPropertiesFile.getPropertyValue("StashPassword"))));
				} catch (ParseException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
					logger.error("No match for user/password combination");
				}

				// add SIZE to Metrics Using CreateMetric method
				// metricArray is what is used to build Introscope metrics
				metricArray.add(createMetric("LongCounter", metricRootLocation + ":Number of Repos",
						mainWebServiceJSON.get("size")));
				metricArray.add(
						createMetric("LongCounter", metricRootLocation + ":Limit", mainWebServiceJSON.get("limit")));
				metricArray.add(createMetric("StringEvent", metricRootLocation + ":Is Last Page",
						mainWebServiceJSON.get("isLastPage")));

				// *****Grab Project & Repository info******

				JSONArray values = (JSONArray) mainWebServiceJSON.get("values");

				//Removed the # of Users metric
//				// ****** # of Users *****************
//				String UserURL = StashRepoUrl.replaceAll("/repos", "/users");
//				metricArray.add(createMetric("LongCounter", metricRootLocation + ":Number of Repos",
//						mainWebServiceJSON.get("size")));
//
//				JSONObject userJSON = null;
//				try {
//					userJSON = (JSONObject) new JSONParser().parse(
//							WebServiceHandler.callURL(UserURL, (GetPropertiesFile.getPropertyValue("StashUserName")),
//									(GetPropertiesFile.getPropertyValue("StashPassword"))));
//				} catch (ParseException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//				metricArray.add(
//						createMetric("LongCounter", metricRootLocation + ":Number of Users", userJSON.get("size")));

				// ******For loop over the Number of Values*******
				// .size = Array size
				for (int i = 0; i < values.size(); i++) {

					JSONObject REPO = (JSONObject) values.get(i);
					String metricLocation = metricRootLocation + "|" + ((JSONObject) REPO.get("project")).get("name")
							+ "|" + REPO.get("name");
							// Casting to Array

					// ********Get URL for each repository******
					String RepoURL = (String) ((JSONObject) ((JSONArray) ((JSONObject) REPO.get("links")).get("self"))
							.get(0)).get("href");
					

			//		Break = Stops loop totally.  i.e. Get out of loop
			//		Return = Kills program
					if (! RepoURL.split("/")[3].startsWith("projects")) continue;		
					
					String project = RepoURL.split("/") [4];
					String repository = RepoURL.split("/") [6];

					// Get the PROJECT & REPO name
//					String project = (String) ((JSONObject) REPO.get("project")).get("key");
//					String repository = (String) REPO.get("name");
					// Update URL to Pull-Request URL
					// Split URL based on the "/"
//					RepoURL = "http://" + RepoURL.split("/")[2] + "/rest/api/1.0/projects/" + project + "/repos/"
//							+ repository + "/pull-requests";
					
					try {
						RepoURL = GetPropertiesFile.getPropertyValue("HTTPorHTTPS") + "://" + RepoURL.split("/")[2] + "/rest/api/1.0/projects/" + URLEncoder.encode(project,"UTF-8") + "/repos/"
								+ URLEncoder.encode(repository,"UTF-8") + "/pull-requests?limit=1000";
					} catch (UnsupportedEncodingException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}

					// *****Make Pull-Request WebService call*****
					// Call WebService, but give it new REPO URL
					JSONObject RepoJSON = null;
					try {
						RepoJSON = (JSONObject) new JSONParser().parse(WebServiceHandler.callURL(RepoURL,
								(GetPropertiesFile.getPropertyValue("StashUserName")),
								(GetPropertiesFile.getPropertyValue("StashPassword"))));
					} catch (ParseException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					logger.debug("Pull Request URLs  = " + RepoURL);

					long size = ((Long) RepoJSON.get("size")).intValue();
					// *** If RepoJSON Array does NOT have any pull request info
					// then skip that repository
					// if size not zero do this
					if (size != 0) {
						metricArray.add(createMetric("LongCounter", metricLocation + ":Pull Requests - Total",
								RepoJSON.get("size")));
						metricArray.add(createMetric("StringEvent", metricLocation + ":Is Last Page",
								RepoJSON.get("isLastPage")));
						metricArray.add(createMetric("LongCounter", metricLocation + ":Limit", RepoJSON.get("limit")));

						JSONArray PullState = (JSONArray) RepoJSON.get("values");

						// ***Potential state a Pull Request can be in***
						int opens = 0;
						int merges = 0;
						int declines = 0;

						// ****Iterate over size of the PullState Array
						for (int m = 0; m < PullState.size(); m++) {

							JSONObject RepoPullState = (JSONObject) PullState.get(m);
							// Find Pull Request state by getting the
							// "state" key
							String PullStatus = (String) RepoPullState.get("state");

							if (PullStatus.matches("OPEN")) {
								opens++;
							}

							else if (PullStatus.matches("MERGE")) {
								merges++;
							}

							else if (PullStatus.matches("DECLINE")) {
								declines++;
							}

							else {
								logger.debug("State of Pull Request NOT Found");
							}
						}
						// Create metrics for Number of each Pull Request types
						metricArray.add(createMetric("LongCounter", metricLocation + ":Pull Requests - Open", opens));
						metricArray
								.add(createMetric("LongCounter", metricLocation + ":Pull Requests - Merged", merges));
						metricArray.add(
								createMetric("LongCounter", metricLocation + ":Pull Requests - Declined", declines));
					}
				}
				metricArray.add(createMetric("StringEvent", metricRootLocation + ":Plugin Success", ("YES")));

				// Report TimeStamp of last plugin RunTime
				long timestamp = System.currentTimeMillis();
				metricArray.add(createMetric("TimeStamp", metricRootLocation + ":Last Reporting Interval", timestamp));

				JSONObject metricsToEPAgent = new JSONObject();
				// pre-pends "metrics" to the 'metricArray as this is required
				// by EPAgent API
				metricsToEPAgent.put("metrics", metricArray);

				// Using Properties File to pass in 'sendMetric' parameters
				WebServiceHandler.sendMetric(metricsToEPAgent.toString(),
						(GetPropertiesFile.getPropertyValue("EPAgentHost")),
						Integer.valueOf(GetPropertiesFile.getPropertyValue("EPAgentPort")));
			}

			public JSONObject createMetric(String type, String name, Object value) {

				JSONObject metric = new JSONObject();
				metric.put("type", type);
				metric.put("name", name);
				metric.put("value", value);
				return metric;
			}
		};

		// Executer to trigger runnable thread. Delay time is defined in the
		// stash.properties DEFAULTS to 5 minutes if no value provided or below 5 minutes
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		if ((GetPropertiesFile.getPropertyValue("delaytime").isEmpty()) || Integer.parseInt(GetPropertiesFile.getPropertyValue("delaytime")) < 15) {
			executor.scheduleAtFixedRate(servers, 0, 15, TimeUnit.MINUTES);
		} 
			else {
			executor.scheduleAtFixedRate(servers, 0, Integer.parseInt(GetPropertiesFile.getPropertyValue("delaytime")),
					TimeUnit.MINUTES);
		}
		// Loop to keep Main Thread alive
		while (true) {
			try {
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
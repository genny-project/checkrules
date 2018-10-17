package life.genny;



import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.kie.api.KieBase;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vertx.core.json.DecodeException;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import life.genny.qwandautils.GennySettings;
import life.genny.qwandautils.KeycloakUtils;



public class App {
	
	protected static final Logger log = org.apache.logging.log4j.LogManager
			.getLogger(MethodHandles.lookup().lookupClass().getCanonicalName());

	private Map<String, KieBase> kieBaseCache = new HashMap<String, KieBase>();

	KieServices ks = KieServices.Factory.get();

	Set<String> realms = new HashSet<String>();


	@Parameter(names = "--help", help = true)
	private boolean help = false;

	@Parameter(names = { "--rulesdir", "-r" }, description = "Rules Dir", required = false)
	List<String> rulesdirs;


	@Parameter(names = { "--verbose", "-v" }, description = "disables quiet mode (verbose)")
	private boolean verbose = true;

	public static void main(String... args) {
		App main = new App();

		if (main.verbose) {
			System.out.println("Genny Drools Rules Checker V1.0\n");
		}
		JCommander jCommander = new JCommander(main, args);
		if ((main.help) ) {
			jCommander.usage();
			return;
		}
			main.runs();
		
	}

	public void runs() {

		if ((rulesdirs == null)||rulesdirs.isEmpty()) {
			rulesdirs = new ArrayList<String>();
			rulesdirs.add("/rules"); // default
		}
		
		for (String rulesdir : rulesdirs) {
			System.out.println("Rulesdir = "+rulesdir);
			loadInitialRules(rulesdir);
		}
		
		System.out.println("Finished");
  
	}

	/**
	 * @param vertx
	 * @return
	 */
	public void loadInitialRules(final String rulesDir) {
		log.info("Loading Rules and workflows!!!");
		setKieBaseCache(new HashMap<String, KieBase>()); // clear
		// List<Tuple2<String, String>> life.genny.rules = processFile(rulesDir);
		// setupKieRules("life.genny.rules", life.genny.rules);

		List<Tuple3<String, String, String>> rules = processFileRealms("genny", rulesDir);

		realms = getRealms(rules);
		realms.stream().forEach(System.out::println);
		realms.remove("genny");
		log.info("Setting up Genny Rules");
		if (realms.isEmpty()) {
			setupKieRules("genny", rules); // run genny life.genny.rules first
		} else {
			for (String realm : realms) {
				setupKieRules(realm, rules);
			}
		}

	}
	List<Tuple3<String, String, String>> processFileRealms(final String realm, String inputFileStrs) {
		List<Tuple3<String, String, String>> rules = new ArrayList<Tuple3<String, String, String>>();

		String[] inputFileStrArray = inputFileStrs.split(";"); // allow multiple life.genny.rules dirs

		for (String inputFileStr : inputFileStrArray) {
			File file = new File(inputFileStr);
			String fileName = inputFileStr.replaceFirst(".*/(\\w+).*", "$1");
			String fileNameExt = inputFileStr.replaceFirst(".*/\\w+\\.(.*)", "$1");
			if (!file.isFile()) { // DIRECTORY
				if (!fileName.startsWith("XX")) {
					String localRealm = realm;
					if (fileName.startsWith("prj_") || fileName.startsWith("PRJ_")) {
						localRealm = fileName.substring("prj_".length()).toLowerCase(); // extract realm name
					}
					List<String> filesList = null;

					if (Vertx.currentContext() != null) {
						filesList = Vertx.currentContext().owner().fileSystem().readDirBlocking(inputFileStr);
					} else {
						final File folder = new File(inputFileStr);
						final File[] listOfFiles = folder.listFiles();
						if (listOfFiles != null) {
							filesList = new ArrayList<String>();
							for (File f : listOfFiles) {
								filesList.add(f.getAbsolutePath());
							}
						} else {
							log.error("No life.genny.rules files located in " + inputFileStr);
						}
					}

					for (final String dirFileStr : filesList) {
						List<Tuple3<String, String, String>> childRules = processFileRealms(localRealm, dirFileStr); // use
																														// directory
																														// name
																														// as
						// rulegroup
						rules.addAll(childRules);
					}
				}

			} else {
				String nonVertxFileText = null;
				Buffer buf = null;
				if (Vertx.currentContext() != null) {
					buf = Vertx.currentContext().owner().fileSystem().readFileBlocking(inputFileStr);
				} else {
					try {
						nonVertxFileText = getFileAsText(inputFileStr);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				try {
					if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("drl"))) { // ignore files that
																									// start
																									// with XX
						String ruleText = null;
						if (Vertx.currentContext() != null) {
							ruleText = buf.toString();
						} else {
							ruleText = nonVertxFileText;
						}

						Tuple3<String, String, String> rule = (Tuple.of(realm, fileName + "." + fileNameExt, ruleText));
						String filerule = null;

						try {
							// filerule =
							// inputFileStr.substring(inputFileStr.indexOf("/life.genny.rules/"));
							log.info("(" + realm + ") Loading in Rule:" + rule._1 + " of " + inputFileStr);
							rules.add(rule);
						} catch (StringIndexOutOfBoundsException e) {
							log.error("Bad parsing [" + inputFileStr + "]");
						}
					} else if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("bpmn"))) { // ignore files
																											// that
																											// start
																											// with XX
						String bpmnText = null;
						if (Vertx.currentContext() != null) {
							bpmnText = buf.toString();
						} else {
							bpmnText = nonVertxFileText;
						}

						Tuple3<String, String, String> bpmn = (Tuple.of(realm, fileName + "." + fileNameExt, bpmnText));
						log.info(realm + " Loading in BPMN:" + bpmn._1 + " of " + inputFileStr);
						rules.add(bpmn);
					} else if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("xls"))) { // ignore files
																										// that
																										// start with XX
						String xlsText = null;
						if (Vertx.currentContext() != null) {
							xlsText = buf.toString();
						} else {
							xlsText = nonVertxFileText;
						}

						Tuple3<String, String, String> xls = (Tuple.of(realm, fileName + "." + fileNameExt, xlsText));
						log.info(realm + " Loading in XLS:" + xls._1 + " of " + inputFileStr);
						rules.add(xls);
					}

				} catch (final DecodeException dE) {

				}

			}
		}
		return rules;
	}

	public Map<String, KieBase> getKieBaseCache() {
		return kieBaseCache;
	}

	public void setKieBaseCache(Map<String, KieBase> kieBaseCache) {
		this.kieBaseCache = kieBaseCache;

	}

	private String getFileAsText(final String inputFilePath) throws IOException {
		File file = new File(inputFilePath);
		final BufferedReader in = new BufferedReader(new FileReader(file));
		String ret = "";
		String line = null;
		while ((line = in.readLine()) != null) {
			ret += line;
		}
		in.close();

		return ret;
	}

	public static Set<String> getRealms(final List<Tuple3<String, String, String>> rules) {
		Set<String> realms = new HashSet<String>();

		for (Tuple3<String, String, String> rule : rules) {
			String realm = rule._1;
			realms.add(realm);
		}
		return realms;
	}

	public Integer setupKieRules(final String realm, final List<Tuple3<String, String, String>> rules) {
		Integer count = 0;
		try {
			// load up the knowledge base
			final KieFileSystem kfs = ks.newKieFileSystem();

			// final String content =
			// new
			// String(Files.readAllBytes(Paths.get("src/main/resources/validateApplicant.drl")),
			// Charset.forName("UTF-8"));
			// log.info("Read New Rules set from File");

			// Write each rule into it's realm cache
			for (final Tuple3<String, String, String> rule : rules) {
				// test each rule as it gets entered
				log.info("Checking rule "+rule._1+" ["+rule._2+"]");
				if (writeRulesIntoKieFileSystem(realm, rules, kfs, rule)) {
					count++;
				}
				final KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll();
				if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
					log.error("Error in Rules for realm " + realm+ " for rule file "+rule._2);
					log.info(kieBuilder.getResults().toString());
					log.info(realm + " life.genny.rules NOT installed\n");
					return -1;
				}
			}

			final KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll();
			if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
				log.error("Error in Rules for realm " + realm);
				log.info(kieBuilder.getResults().toString());
				log.info(realm + " life.genny.rules NOT installed\n");
			} else {

				ReleaseId releaseId = kieBuilder.getKieModule().getReleaseId();

				final KieContainer kContainer = ks.newKieContainer(releaseId);
				final KieBaseConfiguration kbconf = ks.newKieBaseConfiguration();
				final KieBase kbase = kContainer.newKieBase(kbconf);

				log.info("Put life.genny.rules KieBase into Custom Cache");
				if (getKieBaseCache().containsKey(realm)) {
					getKieBaseCache().remove(realm);
					log.info(realm + " removed");
				}
				getKieBaseCache().put(realm, kbase);
				log.info(realm + " life.genny.rules installed\n");
			}

		} catch (final Throwable t) {
			t.printStackTrace();
		}
		return count;
	}

	/**
	 * @param realm
	 * @param life.genny.rules
	 * @param kfs
	 * @param rule
	 */
	private boolean writeRulesIntoKieFileSystem(final String realm, final List<Tuple3<String, String, String>> rules,
			final KieFileSystem kfs, final Tuple3<String, String, String> rule) {
		boolean ret = false;

		if (rule._1.equalsIgnoreCase("genny") || rule._1.equalsIgnoreCase(realm)) {
			// if a realm rule with same name exists as the same name as a genny rule then
			// ignore the genny rule
			if ((rule._1.equalsIgnoreCase("genny")) && (!"genny".equalsIgnoreCase(realm))) {
				String filename = rule._2;
				// check if realm rule exists, if so then continue
				// if (life.genny.rules.stream().anyMatch(item -> ((!realm.equals("genny")) &&
				// realm.equals(item._1()) && filename.equals(item._2()))))
				// {
				// log.info(realm+" - Overriding genny rule "+rule._2);
				// return;
				// }
				for (Tuple3<String, String, String> ruleCheck : rules) { // look for life.genny.rules that are not genny
																			// life.genny.rules
					String realmCheck = ruleCheck._1;
					if (realmCheck.equals(realm)) {

						String filenameCheck = ruleCheck._2;
						if (filenameCheck.equalsIgnoreCase(filename)) {
							log.info("Ditching the genny rule because higher rule overrides:" + rule._1 + " : "
									+ rule._2);
							return false; // do not save this genny rule as there is a proper realm rule with same name
						}
					}

				}
			}
			if (rule._2.endsWith(".drl")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.DRL));
			}
			if (rule._2.endsWith(".bpmn")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.BPMN2));
			} else if (rule._2.endsWith(".xls")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				// Needs t handle byte[]
				// kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new
				// FileReader(rule._2))
				// .setResourceType(ResourceType.DTABLE));

			} else {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.DRL));
			}
			return true;
		}
		return ret;
	}

	public Map<String, Object> getDecodedTokenMap(final String token) {
		Map<String, Object> decodedToken = null;
		if ((token != null) && (!token.isEmpty())) {
			// Getting decoded token in Hash Map from QwandaUtils
			decodedToken = KeycloakUtils.getJsonMap(token);
			/*
			 * Getting Prj Realm name from KeyCloakUtils - Just cheating the keycloak realm
			 * names as we can't add multiple realms in genny keyclaok as it is open-source
			 */
			final String projectRealm = KeycloakUtils.getPRJRealmFromDevEnv();
			if ((projectRealm != null) && (!projectRealm.isEmpty())) {
				decodedToken.put("realm", projectRealm);
			} else {
				// Extracting realm name from iss value
				final String realm = (decodedToken.get("iss").toString()
						.substring(decodedToken.get("iss").toString().lastIndexOf("/") + 1));
				// Adding realm name to the decoded token
				decodedToken.put("realm", realm);
			}
		}
		return decodedToken;
	}

	public List<Tuple2<String, Object>> getStandardGlobals() {
		List<Tuple2<String, Object>> globals = new ArrayList<Tuple2<String, Object>>();
		String RESET = "\u001B[0m";
		String RED = "\u001B[31m";
		String GREEN = "\u001B[32m";
		String YELLOW = "\u001B[33m";
		String BLUE = "\u001B[34m";
		String PURPLE = "\u001B[35m";
		String CYAN = "\u001B[36m";
		String WHITE = "\u001B[37m";
		String BOLD = "\u001b[1m";

		globals.add(Tuple.of("LOG_RESET", RESET));
		globals.add(Tuple.of("LOG_RED", RED));
		globals.add(Tuple.of("LOG_GREEN", GREEN));
		globals.add(Tuple.of("LOG_YELLOW", YELLOW));
		globals.add(Tuple.of("LOG_BLUE", BLUE));
		globals.add(Tuple.of("LOG_PURPLE", PURPLE));
		globals.add(Tuple.of("LOG_CYAN", CYAN));
		globals.add(Tuple.of("LOG_WHITE", WHITE));
		globals.add(Tuple.of("LOG_BOLD", BOLD));
		globals.add(Tuple.of("REACT_APP_QWANDA_API_URL", GennySettings.qwandaServiceUrl));
		// globals.add(Tuple.of("REACT_APP_VERTX_URL", vertxUrl));
		// globals.add(Tuple.of("KEYCLOAKIP", hostIp));
		return globals;
	}




}

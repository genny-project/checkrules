package life.genny;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.core.config.Configurator;

import org.apache.commons.lang3.StringUtils;
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
	private Map<String, String> importMap = new HashMap<String, String>();
	private Map<String, String> fileMap = new HashMap<String, String>();

	KieServices ks = KieServices.Factory.get();

	Set<String> realms = new HashSet<String>();

	@Parameter(names = "--help", help = true)
	private boolean help = false;

	@Parameter(names = { "--drlfile", "-d" }, description = "drl file path")
	private String drlfile;

	@Parameter(names = { "--all", "-a" }, description = "check all rules in one go")
	private boolean doAllAtOnce = false;
	
	@Parameter(names = { "--rulesdir", "-r" }, description = "Rules Dir", required = false)
	List<String> rulesdirs;

	@Parameter(names = { "--verbose", "-v" }, description = "disables quiet mode (verbose)")
	private boolean verbose = true;

	@Parameter(names = { "--fix", "-f" }, description = "try and fix")
	private boolean fix = false;

	private Integer ruleCount = 1;

	public static void main(String... args) {
		App main = new App();

		if (main.verbose) {
			System.out.println("Genny Drools Rules Checker V7.1.0\n");
		}
		JCommander jCommander = new JCommander(main, args);
		if ((main.help)) {
			jCommander.usage();
			System.exit(-1);
		}
		Map<String, String> envs = new HashMap<String, String>();
		String userHome = System.getenv("HOME");
		envs.put("M2_HOME", userHome + "/.m2");
		try {
			setEnv(envs);
		} catch (Exception e) {

		}

		Set<String> errors = main.runs();

		if (errors.isEmpty()) {
			System.out.println("All good!");
			return;
		} else {
			System.exit(errors.size());
		}

	}

	public Set<String> runs() {

		setupImportMap();
		
		Set<String> errors = new HashSet<String>();

		if (drlfile==null) {

		if ((rulesdirs == null) || rulesdirs.isEmpty()) {
			rulesdirs = new ArrayList<String>();
			rulesdirs.add("/rules"); // default
		}


		for (String rulesdir : rulesdirs) {
			System.out.println("Rulesdir = " + rulesdir);
			Set<String> result = loadInitialRules(rulesdir);
			if (result != null) {
				errors.addAll(result);
			}
		}
		} else {

			Set<String> result = loadInitialRules(drlfile);
			if (result != null) {
				errors.addAll(result);
			}

		}

		System.out.println("Finished with " + errors.size() + " errors");

		return errors;

	}

	/**
	 * @param vertx
	 * @return
	 */
	public Set<String> loadInitialRules(final String rulesDir) {
		Set<String> errors = new HashSet<String>();
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
			errors.addAll(setupKieRules("genny", rules)); // run genny life.genny.rules first
		} else {
			for (String realm : realms) {
				errors.addAll(setupKieRules(realm, rules));
			}
		}
		return errors;
	}

	List<Tuple3<String, String, String>> processFileRealms(final String realm, String inputFileStrs) {
		List<Tuple3<String, String, String>> rules = new ArrayList<Tuple3<String, String, String>>();

		String[] inputFileStrArray = inputFileStrs.split(";"); // allow multiple life.genny.rules dirs

		for (String inputFileStr : inputFileStrArray) {
			File file = new File(inputFileStr);
			String fileName = inputFileStr.replaceFirst(".*/(\\w+).*", "$1");
			String fileNameExt = inputFileStr.replaceFirst(".*/\\w+\\.(.*)", "$1");
			if (!file.isFile()) { // DIRECTORY
				if ((!fileName.startsWith("XX"))) {
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
						File f = new File(fileName + "." + fileNameExt);
						fileMap.put(f.getName(), inputFileStr);
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

	private List<String> getFileAsList(final String inputFilePath) throws IOException {
		List<String> lines = new ArrayList<String>();

		File file = new File(inputFilePath);
		final BufferedReader in = new BufferedReader(new FileReader(file));

		String line = null;
		boolean packageline = false;
		while ((line = in.readLine()) != null) {
			if (line.startsWith("package")) {
				if (!packageline) {
					packageline = true;
					lines.add(line);
				} else {
					log.error("Fix Duplicate Package declaration");
				}
			} else {
				if (packageline) {
					lines.add(line); // only add lines once the package appears
				}
			}
		}
		in.close();
		return lines;
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

	public static void printVersion(Class<?> clazz) {
        Package p = clazz.getPackage();
        System.out.printf("%s%n  Title: %s%n  Version: %s%n  Vendor: %s%n",
                          clazz.getName(),
                          p.getImplementationTitle(),
                          p.getImplementationVersion(),
                          p.getImplementationVendor());
    }

	public Set<String> setupKieRules(final String realm, final List<Tuple3<String, String, String>> rules) {
		Set<String> errors = new HashSet<String>();
		Integer count = 0;
		try {
			// load up the knowledge base
			if (ks == null) {
				ks = KieServices.Factory.get();
			}
			final KieFileSystem kfs = ks.newKieFileSystem();
			int errorCount = 0;

			// final String content =
			// new
			// String(Files.readAllBytes(Paths.get("src/main/resources/validateApplicant.drl")),
			// Charset.forName("UTF-8"));
			// log.info("Read New Rules set from File");

			
			if (doAllAtOnce) {
				Set<String> rets = new HashSet<>();
				for (final Tuple3<String, String, String> arule : rules) {
					boolean ruleok = false;
					Tuple3<String, String, String> rule = arule;
						writeRulesIntoKieFileSystem(realm, rules, kfs, rule);
				}

				// Dirty trick to stop KieBuilder from printing to screen
				// The library uses SLF4J and logback for its internal logging
				ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
				ch.qos.logback.classic.Level lvl = root.getLevel();
				root.setLevel(ch.qos.logback.classic.Level.OFF);
				// Build to find errors
				final KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll();
				// Reset log level after building
				root.setLevel(lvl);
				System.out.println("\n");

				if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
					// log.error("Error in Rules for realm " + realm + " for rule file " + rule._2);
					log.info(kieBuilder.getResults().toString());
					rets.add(kieBuilder.getResults().toString());
				}
				
				return rets;
			}
			
			
			// Write each rule into it's realm cache
			for (final Tuple3<String, String, String> arule : rules) {
				boolean ruleok = false;
				Tuple3<String, String, String> rule = arule;
				int index = 1;
				int loopcount = 0;
				while (!ruleok) {
					loopcount++;
					// test each rule as it gets entered
					log.info("Checking rule " + ruleCount + " of " + rules.size() + " " + rule._1 + " [" + rule._2
							+ "] pass " + (index++));
					if (writeRulesIntoKieFileSystem(realm, rules, kfs, rule)) {
						count++;
					}
					final KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll();
					if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
						// log.error("Error in Rules for realm " + realm + " for rule file " + rule._2);
						log.info(kieBuilder.getResults().toString());

						if (fix) {
							if (rule._2.equalsIgnoreCase("10_SendOfferNotificationToIntern.drl")) {
								log.info("stop here");
							}

							// dumbly get rid of duplicate packages
							List<String> filelinesdedup = getFileAsList(fileMap.get(rule._2));
							PrintWriter pw2 = new PrintWriter(new FileWriter(fileMap.get(rule._2)));
							for (int j = 0; j < filelinesdedup.size(); j++) {
								pw2.write(filelinesdedup.get(j) + "\n");
							}
							pw2.close();
							pw2.flush();

							// extract the error lines
							for (Message errorMsg : kieBuilder.getResults().getMessages()) {
								String linetext = errorMsg.getText();

								if (loopcount > 10) {
									log.error("Error #" + (++errorCount) + " Yikes! Cannot handle this one! ");
									for (Message errorMsg2 : kieBuilder.getResults().getMessages()) {
										String linetext2 = errorMsg2.getText();
										log.error(linetext2);
									}
									ruleok = true;
									break;
								}

								if (linetext.contains("resolve")) {
									log.info("Error #" + (++errorCount) + " Fix Missing Import");
									String[] lines = linetext.split("\n");
									Set<String> imports = new HashSet<String>();
									for (String line : lines) {
										line = line.trim();
										Pattern pattern = Pattern.compile("(\\S+)\\s+cannot be resolved to a type.*");
										Matcher matcher = pattern.matcher(line);
										if (matcher.matches()) {
											// log.info("Found Missing Import - " + matcher.group(1) + " in file " +
											// rule._2);
											imports.add(matcher.group(1));
										} else {
											pattern = Pattern.compile(
													"Rule\\sCompilation\\serror\\s(\\S+)\\s+cannot be resolved to a type");
											matcher = pattern.matcher(line);
											if (matcher.matches()) {
												// log.info("Found Missing Import - " + matcher.group(1) + " in file " +
												// rule._2);
												imports.add(matcher.group(1));

											}
											else {
												pattern = Pattern.compile(
														".*\\s+(\\S+)\\s+resolves\\sto\\sa\\spackage");
												matcher = pattern.matcher(line);
												if (matcher.matches()) {
													// log.info("Found Missing Import - " + matcher.group(1) + " in file " +
													// rule._2);
													imports.add(matcher.group(1));

												}
											else {
												pattern = Pattern
														.compile("Unable\\sto\\sresolve\\sObjectType\\s\\'(\\S+)\\'");
												matcher = pattern.matcher(line);
												if (matcher.matches()) {
													// log.info("Found Missing Import - " + matcher.group(1) + " in file
													// " +
													// rule._2);
													imports.add(matcher.group(1));

												}

												else {
													pattern = Pattern.compile(
															"Rule\\sCompilation\\serror\\s(\\S+)\\s+cannot be resolved");
													matcher = pattern.matcher(line);
													if (matcher.matches()) {
														// log.info("Found Missing Import - " + matcher.group(1) + " in
														// file
														// " +
														// rule._2);
														imports.add(matcher.group(1));
													} else {
														pattern = Pattern.compile("(\\w+)\\s+cannot be resolved");
														matcher = pattern.matcher(line);
														if (matcher.matches()) {
															// log.info("Found Missing Import - " + matcher.group(1) + "
															// in file
															// " +
															// rule._2);
															imports.add(matcher.group(1));
														} else {
															pattern = Pattern.compile(
																	"Rule\\sCompilation\\serror\\sThe\\smethod\\sfilter\\(Predicate\\<\\?\\ssuper\\s(\\S+)\\>\\)\\sin\\sthe\\stype\\sStream\\<(\\S+)\\>\\s+is\\snot\\sapplicable.*");
															matcher = pattern.matcher(line);
															if (matcher.matches()) {

																imports.add(matcher.group(1));
															} else {
																pattern = Pattern.compile(
																		"Type\\smismatch\\:\\scannot\\sconvert\\sfrom\\selement\\stype\\s(\\S+)\\sto\\s(\\S+)");
																matcher = pattern.matcher(line);
																if (matcher.matches()) {

																	// do nothing
																} else {
																	if (line.contains(
																			"is not applicable for the arguments")) {
																		log.error(line);
																		ruleok = true;
																	}
																}
															}
														}
													}
												}
											}
										}
										}
									}
									// log.info("found " + imports.size() + " imports to be added " + imports);
									String importsLine = "";
									for (String importLine : imports) {
										String importstatement = importMap.get(importLine);
										if (importstatement == null) {
											log.error("Please add import for " + importLine);
											ruleok = true; // to hop out of loop
										} else {
											String l = "import " + importMap.get(importLine) + ";";
											importsLine += l + "\n";
										}
									}
									// log.info(importsLine);
									// Now add to existing file
									// log.info("full filename = " + fileMap.get(rule._2));
									// now add the imports to the file
									List<String> filelines = getFileAsList(fileMap.get(rule._2));
									// Now write back
									if (!filelines.isEmpty()) {
										int startIndex = 0;
										while (StringUtils.isBlank(filelines.get(startIndex))) {
											startIndex++;
										}
										PrintWriter pw = new PrintWriter(new FileWriter(fileMap.get(rule._2)));

										pw.write(filelines.get(startIndex) + "\n"); // package
										pw.write("\n");
										pw.write(importsLine);
										for (int j = 1; j < filelines.size(); j++) {
											pw.write(filelines.get(j) + "\n");
										}

										pw.close();
										pw.flush();
									}
									break;
								} else {
									if (linetext.contains("mismatched input '<eof>'")) {
										log.info("Error #" + (++errorCount) + " Fix // comments");
										List<String> filelines = getFileAsList(fileMap.get(rule._2));
										// Now write back
										PrintWriter pw = new PrintWriter(new FileWriter(fileMap.get(rule._2)));

										for (int j = 0; j < filelines.size(); j++) {
											String line = filelines.get(j);
											if (line.contains("//")) {
												// is there // on the line?
												Pattern pattern = Pattern.compile("^(.*)[^:]\\/\\/(.*)");
												Matcher matcher = pattern.matcher(line);
												if (matcher.matches()) {
													// log.info(line);
													// log.info("found [" + matcher.group(1) + "]" + "/* " +
													// matcher.group(2)
													// + " */");
													// remove any internal comments
													String commentText = matcher.group(2);
													commentText = commentText.replaceAll("/\\*", "");
													commentText = commentText.replaceAll("\\*/", "");
													pw.write(matcher.group(1) + "/* " + commentText + " */" + "\n");
												} else {
													pw.write(line + "\n");
												}
											} else {
												pw.write(line + "\n");
											}
											// pw.write(filelines.get(j)+"\n");
										}

										pw.close();
										pw.flush();
										break;
									} else {
										if (linetext.contains(
												"package, unit, import, global, declare, function, rule, query")) {
											log.info("Error #" + (++errorCount) + " Fix missing semi colon");
											List<String> filelines = getFileAsList(fileMap.get(rule._2));
											// Now write back
											PrintWriter pw = new PrintWriter(new FileWriter(fileMap.get(rule._2)));

											for (int j = 0; j < filelines.size(); j++) {
												String line = filelines.get(j);
												if (line.contains("import")) {
													// is there // on the line?
													Pattern pattern = Pattern.compile("^\\s*import(.*)");
													Matcher matcher = pattern.matcher(line);
													if (matcher.matches()) {
														// log.info(line);
														String importline = matcher.group(1).trim();
														if (!importline.endsWith(";")) {
															pw.write(line + ";" + "\n");
														}
													} else {
														pw.write(line + "\n");
													}
												} else {
													pw.write(line + "\n");
												}
												// pw.write(filelines.get(j)+"\n");
											}

											pw.close();
											pw.flush();
											break;
										} else {
											log.error("Error #" + (++errorCount) + " Unknown error - " + linetext);
											ruleok = true;
										}
									}
								}
							}
							String ruletext = getFileAsText(fileMap.get(rule._2));
							rule = Tuple.of(arule._1, arule._2, ruletext); // update the rule with the fixed text
						} else {
							ruleok = true;
							for (Message msg : kieBuilder.getResults().getMessages()) {
								if (msg.getText().contains("ERR")) {
									errors.add(msg.getText());
								}
							}
						}
					} else {
						ruleok = true;
						ruleCount++;
					}

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
		return errors;
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
				final String inMemoryDrlFileName = "src/main/resources/life/genny/rules/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.DRL));
			} else if (rule._2.endsWith(".bpmn")) {
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

	private void setupImportMap() {
		try {
			String importFile = "./imports.txt";
			File file = new File(importFile);
			BufferedReader in;

			in = new BufferedReader(new FileReader(file));

			String ret = "";
			String line = null;
			while ((line = in.readLine()) != null) {
				// log.info("IMPORT:"+line);
				Pattern pattern = Pattern.compile("^import\\s+(.*);");
				Matcher matcher = pattern.matcher(line);
				if (matcher.matches()) {
					String iline = matcher.group(1);
					// log.info(iline);
					Pattern pattern2 = Pattern.compile(".*\\.(\\S+)\\s*");
					Matcher matcher2 = pattern2.matcher(iline);
					if (matcher2.matches()) {
						String iline2 = matcher2.group(1);
						String iline2trimmed = iline2.trim();
						if (StringUtils.isAllUpperCase(iline2trimmed) && !(iline2trimmed.equals("GPS"))) {
							Pattern pattern3 = Pattern.compile(".*\\.(\\S+\\.\\S+)\\s*");
							Matcher matcher3 = pattern3.matcher(iline);
							if (matcher3.matches()) {
								iline2 = matcher3.group(1); // get the enum
							}
							// remove the last bit
							String lastbit = "."+iline2trimmed;
							iline = StringUtils.removeEnd(iline,lastbit );
						}
						// log.info(iline2);
						importMap.put(iline2.trim(), iline.trim());
						log.info(iline2 + ":" + iline);
					}
					// log.info(matcher.group(2));
				}

			}
			in.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// TODO, this looks ugly. Why are enums being used???? should be baseentity
		// codes. fix

		importMap.put("ViewType.Form", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.Table", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.List", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.SplitView", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.Custom", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.Bucket", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.Detail", "life.genny.utils.Layout.ViewType");
		importMap.put("ViewType.Tab", "life.genny.utils.Layout.ViewType");
		importMap.put("SearchEntity.StringFilter.LIKE", "life.genny.qwanda.entity.SearchEntity.StringFilter.*");
		importMap.put("GennySettings.hostIP", "life.genny.qwandautils.GennySettings");
		importMap.put("VisualControlType.GROUP_LABEL", "life.genny.qwanda.VisualControlType");
		importMap.put("VisualControlType.GROUP_CLICKABLE_WRAPPER", "life.genny.qwanda.VisualControlType");
		importMap.put("VisualControlType.GROUP_WRAPPER", "life.genny.qwanda.VisualControlType");
		importMap.put("ThemePosition.WRAPPER", "life.genny.models.ThemePosition.WRAPPER");
		importMap.put("ThemePosition.CENTRE", "life.genny.models.ThemePosition.CENTRE");
		importMap.put("FramePosition.WRAPPER", "life.genny.models.FramePosition.WRAPPER");
		importMap.put("FramePosition.CENTRE", "life.genny.models.FramePosition.CENTRE");		
	}

	protected static void setEnv(Map<String, String> newenv) throws Exception {
		try {
			Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
			Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
			theEnvironmentField.setAccessible(true);
			Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
			env.putAll(newenv);
			Field theCaseInsensitiveEnvironmentField = processEnvironmentClass
					.getDeclaredField("theCaseInsensitiveEnvironment");
			theCaseInsensitiveEnvironmentField.setAccessible(true);
			Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
			cienv.putAll(newenv);
		} catch (NoSuchFieldException e) {
			Class[] classes = Collections.class.getDeclaredClasses();
			Map<String, String> env = System.getenv();
			for (Class cl : classes) {
				if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
					Field field = cl.getDeclaredField("m");
					field.setAccessible(true);
					Object obj = field.get(env);
					Map<String, String> map = (Map<String, String>) obj;
					map.clear();
					map.putAll(newenv);
				}
			}
		}
	}
}

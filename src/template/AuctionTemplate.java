package template;

//the list of imports
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import logist.LogistPlatform;
import logist.LogistSettings;
import logist.agent.Agent;
import logist.behavior.AuctionBehavior;
import logist.plan.Plan;
import logist.simulation.Vehicle;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;

/**
 * A very simple auction agent that assigns all tasks to its first vehicle and
 * handles them sequentially.
 *
 */
@SuppressWarnings("unused")
public class AuctionTemplate implements AuctionBehavior {

	private static final int INIT_POOL_SIZE = 10;
	private static final int INIT_MAX_ITER = 50000;
	private static final long MIN_TASKS_FOR_SPECULATION = 5;
	private static final long MIN_TASKS_FOR_CENTRALIZED = 10;
	private static final int NB_CENTRALIZED_RUN = 10;
	private static final int WINDOW_SIZE = 5;
	private static final double GUESS_ACCEPTANCE_PERCENT = 0.4;
	private static final double BID_MARGIN_STEP_PERCENT = 0.05;
	private static final double BID_MIN_MARGIN_PERCENT = 0.05;
	private static final double BID_MAX_MARGIN_PERCENT = 0.5;
	
	private static final long MIN_BID = 50;
	
	private static final double TIMEOUT_BID = LogistPlatform.getSettings().get(LogistSettings.TimeoutKey.BID);

	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private City currentCity;

	private long nbTasksHandled = 0;

	private HashSet<Task> ourTasks = new HashSet<Task>();
	private long ourTotalReward = 0;
	private double ourLastCost = 0;
	private double ourTempCost = 0;
	private double currentBidMargin = 0.25;
	private boolean lastGuessUseMargin = false;
	private Long lastGuess = 0l;
	private int ournbTasksHandled = 0;
	private Centralized us = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);
	private Solution bestSolution = null;
	private Solution newBestSol = null;

	private HashSet<Task> theirTasks = new HashSet<Task>();
	private long theirTotalReward = 0;
	private LinkedList<Long> theirLastBids = new LinkedList<Long>();
	private Centralized them = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);

	private double averageEdgeWeight = 0.;
	private static final double MAX_VARIANCE_WEIGHT = 0.4;
	private static final double PREDICTION_ERROR_MARGIN = 0.15;
	private static final double TIME_MARGIN_BID = 0.8;
	private static final double EXPLORATION_RATE = 0.2;
	
	// Optimizations
	private static final boolean EDGE_WEIGHT_OPTI = true;
	private static final boolean SPECULATION_OPTI = true;

	private Map<EdgeCity, Double> weights = new HashMap<EdgeCity, Double>();


	HashMap<Pair<String, String>, Long> lastBiddingOponent = new HashMap<Pair<String,String>, Long>();

	@Override
	public void setup(Topology topology, TaskDistribution distribution,
			Agent agent) {

		this.topology = topology;
		this.distribution = distribution;
		this.agent = agent;
		this.vehicle = agent.vehicles().get(0);
		this.currentCity = vehicle.homeCity();

		long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		this.random = new Random(seed);

		for (City c1 : topology.cities()) {
			for (City c2 : topology.cities()) {
				List<City> path = c1.pathTo(c2);
				double prob = distribution.probability(c1, c2) / topology.cities().size();
				if (prob > 0) {
					for (int i = 0; i < path.size(); i++) {
						City previous;
						if (i == 0) {
							previous = c1;
						}
						else {
							previous = path.get(i - 1);
						}
						City current = path.get(i);
						EdgeCity ec = new EdgeCity(previous, current, previous.distanceTo(current));
						Double weight = weights.get(ec);
						if (weight == null) {
							weight = 0d;
						}
						weights.put(ec, weight + prob);
					}
				}
			}
		}

		for (Map.Entry<EdgeCity, Double> entry : weights.entrySet()) {
			averageEdgeWeight += entry.getValue();
		}
		averageEdgeWeight /= weights.size();
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		Long ourBid = bids[agent.id()];
		// Dangerous if more than 2 companies or only us
		Long theirBid = bids[1 - agent.id()];

		System.out.println();
		System.out.println("Our bid: " + ourBid);
		System.out.println("Their bid: " + theirBid);

		// Add bid to history of their bids
		if (theirLastBids.size() >= WINDOW_SIZE) {
			theirLastBids.remove();
		}
		
		if (theirBid != null) {
			theirLastBids.add(theirBid);
		}

		if (winner == agent.id()) {
			//currentCity = previous.deliveryCity;
			System.out.println("Our agent won by bidding: " + ourBid);
			ournbTasksHandled++;
			ourTotalReward += ourBid;
			ourLastCost = ourTempCost;

			// Remove task
			for (Iterator<Task> i = theirTasks.iterator(); i.hasNext();) {
			    Task element = i.next();
			    if (element.id == previous.id) {
			        i.remove();
			        break;
			    }
			}
			

			// refresh the new best Solution
			bestSolution = newBestSol;
			newBestSol = null;
			
		} else {
			if (theirBid != null) {
				System.out.println("The other agent won by bidding: " + theirBid);
			}

			// Remove task
			for (Iterator<Task> i = ourTasks.iterator(); i.hasNext();) {
			    Task element = i.next();
			    if (element.id == previous.id) {
			        i.remove();
			        break;
			    }
			}

			// Dangerous if more than 2 companies or only us
			if (theirBid != null) {
				theirTotalReward += theirBid;
			}
		}
		System.out.println("******************************************************");
		
		// update the last bid for this task
		if (theirBid != null) {
			lastBiddingOponent.put(new Pair<String, String>(previous.pickupCity.name, previous.deliveryCity.name), theirBid);
		}
	}

	@Override
	public Long askPrice(Task task) {
		long start = System.currentTimeMillis();
		
		double projectedValue = 0d;
		if (EDGE_WEIGHT_OPTI) {
			// Compute biased value by looking at the weight of the path
			Double sum = 0d;
			City current = task.pickupCity;
			for (City next : task.pickupCity.pathTo(task.deliveryCity)) {
				EdgeCity ec = new EdgeCity(current, next, current.distanceTo(next));

				Double value = weights.get(ec);
				if (value == null) {
					value = 0d;
				}

				sum += value;
				current = next;
			}

			sum /= task.pickupCity.pathTo(task.deliveryCity).size();

			// Value between 0 and 2 (included)
			double croppedValue = Math.min(2.0, Math.max(0d, (sum / averageEdgeWeight)));
			// Should be between -(MAX_VARIANCE_WEIGHT / 2) and (MAX_VARIANCE_WEIGHT / 2)
			projectedValue = croppedValue * (MAX_VARIANCE_WEIGHT / 2) - MAX_VARIANCE_WEIGHT / 2;
		}
		
		// US
		ourTasks.add(task);

		// we wait to have at least one solution
		Solution newInitSol = bestSolution == null ? new Solution(0, new AgentTask[agent.vehicles().size()], agent.vehicles(), new int[agent.vehicles().size()]) : bestSolution.clone();
		// firstly, we only add the new task to the current best solution and try
		// centralized on it
		AgentTask p = new AgentTask(task, true);
		AgentTask d = new AgentTask(task, false);
		newInitSol.addTaskForVehicle(0, d, null);
		newInitSol.addTaskForVehicle(0, p, null);

		// we try to find a solution with the old best solution
		us.setInitSolution(newInitSol);
		// we try again with the init solution being the last solution computed
		newBestSol = us.computeCentralized(agent.vehicles(), ourTasks);

		// we get ready to do normal centralized
		us.setInitSolution(null);
		
		// now we try to recompute entierly centralized
		ourTempCost = 0;
		int nbCentralizedRun = 1;
		
		if (bestSolution != null) {
			System.out.println("-1. " + ourLastCost + " " + bestSolution.getTotalCost());
		}
		
		System.out.println("0. " + newBestSol.getTotalCost());
		for (int i = 0; i < NB_CENTRALIZED_RUN; i++) {
			// Vary init solution
			if (i % 2 == 0) {
				us.setInitSolution(null);
				us.setMaxIter((int) (INIT_MAX_ITER * (1 + EXPLORATION_RATE)));
			}
			else {
				us.setInitSolution(newInitSol);
				us.setMaxIter((int) (INIT_MAX_ITER * (1 - EXPLORATION_RATE)));
			}
			
			Solution ourNewSol = us.computeCentralized(agent.vehicles(), ourTasks);
			newBestSol = newBestSol == null || newBestSol.getTotalCost() > ourNewSol.getTotalCost() ? ourNewSol : newBestSol;
			System.out.println((nbCentralizedRun + 1) + ". " + ourNewSol.getTotalCost());
			nbCentralizedRun++;
			double now = (double) (System.currentTimeMillis() - start);
			if (now / nbCentralizedRun + now > TIME_MARGIN_BID * TIMEOUT_BID) {
				System.out.println("BREAK! Too much time (iteration " + nbCentralizedRun + ")");
				break;
			}
		}
		ourTempCost = newBestSol.getTotalCost();

		Long ourMarginalCost = ourLastCost == 0 ? Math.round(ourTempCost) :
							Math.max(0, Math.round(ourTempCost - ourLastCost));


		System.out.println("Our marginal cost: " + ourMarginalCost);

		lastGuessUseMargin = false;
		Long toBid = ourMarginalCost;
		System.out.println("toBid: " + toBid);

		if (nbTasksHandled > 0 && SPECULATION_OPTI) {
			Pair<String, String> pair = new Pair<String, String>(task.pickupCity.name, task.deliveryCity.name);
			Long lastOpponentBidForPair = lastBiddingOponent.get(pair);
			
			// Check if we would bid too low compared to what the other is normally doing
			Long minBid = 1l;
			for (Long l : theirLastBids) {
				minBid *= l;
			}
			minBid = (long) (Math.round(Math.pow(minBid, 1.0/theirLastBids.size())) * (1 - PREDICTION_ERROR_MARGIN));

			if (toBid < minBid && (lastOpponentBidForPair == null || lastOpponentBidForPair >= minBid)) {
				System.out.println("Trying to bid too low (" + toBid + "), changing it to: " + minBid);
				toBid = minBid;
			}
			else {
				if (lastOpponentBidForPair != null && toBid < lastOpponentBidForPair) {
					toBid = (long) ((toBid + lastOpponentBidForPair) * 0.5);
				}
			}
		}
		
		toBid = (long) (toBid * Math.min(1, ((double) (ournbTasksHandled + 1) / MIN_TASKS_FOR_CENTRALIZED)) * (1 + projectedValue));

		// Update weight and distance
		nbTasksHandled++;
		
		return toBid < MIN_BID ? MIN_BID : toBid;
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

		if (!tasks.isEmpty()) {
			Solution sol = Solution.recreateSolutionWithGoodTasks(bestSolution, tasks);

			System.out.println("Agent: " + agent.name());
			System.out.println("Total reward for agent " + agent.id() + " is : " + ourTotalReward);
			System.out.println("Total cost for agent " + agent.id() + " is : " + sol.getTotalCost());
			System.out.println("Total benefice for agent " + agent.id() + " is : " + (ourTotalReward - sol.getTotalCost()));
			System.out.println();

			return createPlanFromSolution(sol);
		}
		else {
			// If nothing

			List<Plan> plans = new ArrayList<Plan>();
			while (plans.size() < vehicles.size()) {
				plans.add(Plan.EMPTY);
			}
			return plans;
		}

	}


	private List<Plan> createPlanFromSolution(Solution solution) {
		List<Plan> toReturn = new ArrayList<Plan>();
		for (int i = 0; i < solution.getVehiclesFirstTask().length; i++) {
			AgentTask current = solution.getVehiclesFirstTask()[i];
			Plan plan = new Plan(solution.getVehicles().get(i).getCurrentCity());
			City currentCity = solution.getVehicles().get(i).getCurrentCity();
			while (current != null) {
				if (current.isPickup()) {
					for (City c : currentCity
							.pathTo(current.getTask().pickupCity)) {
						plan.appendMove(c);
					}
					plan.appendPickup(current.getTask());
					currentCity = current.getTask().pickupCity;
				} else {
					for (City c : currentCity
							.pathTo(current.getTask().deliveryCity)) {
						plan.appendMove(c);
					}
					plan.appendDelivery(current.getTask());
					currentCity = current.getTask().deliveryCity;
				}
				current = current.getNext();
			}
			toReturn.add(plan);
		}

		return toReturn;
	}

	private Plan naivePlan(Vehicle vehicle, TaskSet tasks) {
		City current = vehicle.getCurrentCity();
		Plan plan = new Plan(current);

		for (Task task : tasks) {
			// move: current city => pickup location
			for (City city : current.pathTo(task.pickupCity))
				plan.appendMove(city);

			plan.appendPickup(task);

			// move: pickup location => delivery location
			for (City city : task.path())
				plan.appendMove(city);

			plan.appendDelivery(task);

			// set current city
			current = task.deliveryCity;
		}
		return plan;
	}
}

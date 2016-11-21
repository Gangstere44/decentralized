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
	private static final int INIT_MAX_ITER = 10000;
	private static final long MIN_TASKS_FOR_SPECULATION = 5;
	private static final long MIN_TASKS_FOR_CENTRALIZED = 10;
	private static final int NB_CENTRALIZED_RUN = 1;
	private static final int NB_TRY_WITH_NEW_INIT = 1;
	private static final int WINDOW_SIZE = 5;
	private static final double GUESS_ACCEPTANCE_PERCENT = 0.4;
	private static final double BID_MARGIN_STEP_PERCENT = 0.05;
	private static final double BID_MIN_MARGIN_PERCENT = 0.05;
	private static final double BID_MAX_MARGIN_PERCENT = 0.5;

	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private City currentCity;

	private long nbTasksHandled = 0;
	private double avgTasksWork = 0;

	private HashSet<Task> ourTasks = new HashSet<Task>();
	private long ourTotalReward = 0;
	private double ourLastCost = 0;
	private double ourTempCost = 0;
	private double currentBidMargin = 0.25;
	private boolean lastGuessUseMargin = false;
	private Long lastGuess = 0l;
	private int ournbTasksHandled = 0;
	//private LinkedList<Long> ourLastGuesses = new LinkedList<Long>();
	private Centralized us = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);
	private Solution bestSolution = null;
	private Solution newBestSol = null;

	private HashSet<Task> theirTasks = new HashSet<Task>();
	private double theirLastCost = 0;
	private double theirTempCost = 0;
	private long theirTotalReward = 0;
	private LinkedList<Long> theirLastBids = new LinkedList<Long>();
	private Centralized them = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);

	private double averageEdgeWeight = 0.;
	private static final double MAX_VARIANCE_WEIGHT = 0.4;
	private static final double PREDICTION_ERROR_MARGIN = 0.2;

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
		theirLastBids.add(theirBid);

		if (lastGuessUseMargin) {
			// Check if we are currently guessing opponent's bid well
			if (lastGuess * (1 + GUESS_ACCEPTANCE_PERCENT) < theirBid && currentBidMargin >= BID_MIN_MARGIN_PERCENT + BID_MARGIN_STEP_PERCENT) {
				currentBidMargin -= BID_MARGIN_STEP_PERCENT;
				System.out.println("Changed margin down to: " + currentBidMargin);
			}
			else if (lastGuess * (1 - GUESS_ACCEPTANCE_PERCENT) > theirBid && currentBidMargin <= BID_MAX_MARGIN_PERCENT - BID_MARGIN_STEP_PERCENT) {
				currentBidMargin += BID_MARGIN_STEP_PERCENT;
				System.out.println("Changed margin up to: " + currentBidMargin);
			}
			else {
				System.out.println("Current margin seems good: " + currentBidMargin);
			}
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
		} else {
			System.out.println("The other agent won by bidding: " + theirBid);

			// Remove task
			for (Iterator<Task> i = ourTasks.iterator(); i.hasNext();) {
			    Task element = i.next();
			    if (element.id == previous.id) {
			        i.remove();
			        break;
			    }
			}

			// update the last bid for this task
			lastBiddingOponent.put(new Pair<String, String>(previous.pickupCity.name, previous.deliveryCity.name), theirBid);

			// Dangerous if more than 2 companies or only us
			theirTotalReward += theirBid;
			theirLastCost = theirTempCost;
		}
		System.out.println("******************************************************");
	}

	@Override
	public Long askPrice(Task task) {

		/*
		 * - Use probability distribution : weight undirected graph
		 * - keep track of adversary last bid for a task
		 * - try to use old best soution as initial solution
		 * - keep track of best best solution
		 * - try add the new task to the old best solution before recomputing centralized
		 */

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
		double projectedValue = croppedValue * (MAX_VARIANCE_WEIGHT / 2) - MAX_VARIANCE_WEIGHT / 2;

		// Check work to do and see how good it is
		double distance = task.pickupCity.distanceTo(task.deliveryCity);
		double workPenalty = 0;
		if (nbTasksHandled >= MIN_TASKS_FOR_SPECULATION) {
			double workRatio = (task.weight * distance) / avgTasksWork;
			if (workRatio > 1) {
				workPenalty = 1 - 1 / workRatio;
			}
		}

		System.out.println("Work penalty: " + workPenalty);
		// Put penalties together
		double penalty = workPenalty;


		// US
		ourTasks.add(task);

		// we wait to have at least one solution
		if(bestSolution != null) {
			Solution nextPossibleBestSolution = bestSolution.clone();
			// firstly, we only add the new task to the current best solution and try
			// centralized on it
			AgentTask p = new AgentTask(task, true);
			AgentTask d = new AgentTask(task, false);
			nextPossibleBestSolution.addTaskForVehicle(0, d, null);
			nextPossibleBestSolution.addTaskForVehicle(0, p, null);

			Solution tmpNewBestSol = null;
			newBestSol = null;
			// we try to find a solution with the old best solution
			for(int i = 0; i < NB_CENTRALIZED_RUN; i++) {
				Solution tmpSol = null;
				us.setInitSolution(nextPossibleBestSolution);
				// we try again with the init solution being the last solution computed
				for(int j = 0; j < NB_TRY_WITH_NEW_INIT; j++) {
					tmpSol = us.computeCentralized(agent.vehicles(), ourTasks);
					tmpNewBestSol = tmpNewBestSol == null || tmpNewBestSol.getTotalCost() > tmpSol.getTotalCost() ? tmpSol : tmpNewBestSol;
					us.setInitSolution(tmpSol);
				}

				// we take only if the new solution are better than the previous one
				newBestSol = newBestSol == null || newBestSol.getTotalCost() > tmpNewBestSol.getTotalCost() ? tmpNewBestSol : newBestSol;
			}

			// we get ready to do normal centralized
			us.setInitSolution(null);
		}
		// now we try to recompute entierly centralized
		//ourTasks.add(task);
		ourTempCost = 0;
		for (int i = 0; i < NB_CENTRALIZED_RUN; i++) {
			Solution ourNewSol = us.computeCentralized(agent.vehicles(), ourTasks);
			newBestSol = newBestSol == null || newBestSol.getTotalCost() > ourNewSol.getTotalCost() ? ourNewSol : newBestSol;
			ourTempCost += ourNewSol.getTotalCost();
		}
		ourTempCost /= NB_CENTRALIZED_RUN;

		Long ourMarginalCost = ourLastCost == 0 ? Math.round(ourTempCost) :
							Math.max(0, Math.round(ourTempCost - ourLastCost));

		//THEM
		theirTasks.add(task);
		theirTempCost = 0;
		for (int i = 0; i < NB_CENTRALIZED_RUN; i++) {
			Solution theirNewSol = them.computeCentralized(agent.vehicles(), theirTasks);
			theirTempCost += theirNewSol.getTotalCost();
		}
		theirTempCost /= NB_CENTRALIZED_RUN;

		Long theirMarginalCost = theirLastCost == 0 ? Math.round(theirTempCost) :
							Math.max(0, Math.round(theirTempCost - theirLastCost));

		/*
		// Add guess to history of our guesses of their bids
		if (ourLastGuesses.size() >= WINDOW_SIZE) {
			ourLastGuesses.remove();
		}
		ourLastGuesses.add(theirMarginalCost);
		*/

		System.out.println("Our marginal cost: " + ourMarginalCost);
		System.out.println("Their marginal cost: " + theirMarginalCost);


		lastGuessUseMargin = false;
		Long toBid = (long) (ourMarginalCost * Math.min(1, ((double) (ournbTasksHandled + 1) / MIN_TASKS_FOR_CENTRALIZED))
				* (1 + penalty) * (1 + projectedValue));
		System.out.println("toBid: " + toBid);
		if (ourMarginalCost < theirMarginalCost) {
			lastGuessUseMargin = true;
			toBid += (long) ((theirMarginalCost - ourMarginalCost) * (1 - currentBidMargin));
		}

		if (nbTasksHandled > 0) {
			// Check if we would bid too low compared to what the other is normally doing
			Long minBid = 1l;
			for (Long l : theirLastBids) {
				minBid *= l;
			}
			minBid = (long) (Math.round(Math.pow(minBid, 1.0/theirLastBids.size())) * (1 - PREDICTION_ERROR_MARGIN));

			if (toBid < minBid) {
				System.out.println("Trying to bid too low (" + toBid + "), changing it to: " + minBid);
				toBid = minBid;
			}
		}

		// Update weight and distance
		avgTasksWork = (avgTasksWork * nbTasksHandled + task.weight * distance) / (nbTasksHandled + 1);
		nbTasksHandled++;

		return toBid;

		/*
		if (vehicle.capacity() < task.weight)
			return null;

		long distanceTask = task.pickupCity.distanceUnitsTo(task.deliveryCity);
		long distanceSum = distanceTask
				+ currentCity.distanceUnitsTo(task.pickupCity);
		double marginalCost = Measures.unitsToKM(distanceSum
				* vehicle.costPerKm());

		double ratio = 1.0 + (random.nextDouble() * 0.05 * task.id);
		double bid = ratio * marginalCost;

		return (long) Math.round(bid);
		*/
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

		/*
		int tmp = 0;
		for(int i = 0; i < bestSolution.getVehicles().size(); i++) {
			tmp += bestSolution.getTaskNumber(i);
		}
		System.out.println("Taskset n : " + tasks.size() + " , us : " + tmp);

		System.out.println("+++++++++");
		for(Task t : tasks) {
			System.out.println(t.id + " -- " + t.pickupCity.name + " -> " + t.deliveryCity.name);
		}
		System.out.println("+++++++++");
		*/

		if (!tasks.isEmpty()) {
			//Solution sol = us.computeCentralized(vehicles, tasks);
			Solution sol = Solution.recreateSolutionWithGoodTasks(bestSolution, tasks);

			/*
			tmp = 0;
			for(int i = 0; i < sol.getVehicles().size(); i++) {
				tmp += sol.getTaskNumber(i);
			}
			System.out.println("Taskset n : " + tasks.size() + " , us : " + tmp);
			System.out.println("**********");
			for(Vehicle v : sol.getVehicles()) {
				AgentTask a = sol.getVehiclesFirstTask()[v.id()];

				while(a != null) {
					if(a.isPickup())
					System.out.println(a.getTask().id + " -- " + a.getTask().pickupCity.name + " -> " + a.getTask().deliveryCity.name);

					a = a.getNext();
				}
			}
			System.out.println("**********");
			*/

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

		/*
//		System.out.println("Agent " + agent.id() + " has tasks " + tasks);

		Plan planVehicle1 = naivePlan(vehicle, tasks);

		List<Plan> plans = new ArrayList<Plan>();
		plans.add(planVehicle1);
		while (plans.size() < vehicles.size())
			plans.add(Plan.EMPTY);

		return plans;
		*/
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

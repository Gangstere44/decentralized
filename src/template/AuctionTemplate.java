package template;

//the list of imports
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import logist.Measures;
import logist.behavior.AuctionBehavior;
import logist.agent.Agent;
import logist.simulation.Vehicle;
import logist.plan.Plan;
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
	private static final long MIN_BID = 1000;
	private static final int NB_CENTRALIZED_RUN = 5;
	private static final int WINDOW_SIZE = 10;
	private static final double GUESS_ACCEPTANCE_PERCENT = 0.2;
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
	//private LinkedList<Long> ourLastGuesses = new LinkedList<Long>();
	private Centralized us = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);
	
	private HashSet<Task> theirTasks = new HashSet<Task>();
	private double theirLastCost = 0;
	private double theirTempCost = 0;
	private long theirTotalReward = 0;
	private LinkedList<Long> theirLastBids = new LinkedList<Long>();
	private Centralized them = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);

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
			
			// Dangerous if more than 2 companies or only us
			theirTotalReward += theirBid;
			theirLastCost = theirTempCost;
		}
		System.out.println("******************************************************");
	}

	@Override
	public Long askPrice(Task task) {
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
		ourTempCost = 0;
		for (int i = 0; i < NB_CENTRALIZED_RUN; i++) {
			Solution ourNewSol = us.computeCentralized(agent.vehicles(), ourTasks);
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
		Long toBid = (long) (ourMarginalCost * (1 + penalty));
		System.out.println("toBid: " + toBid);
		if (ourMarginalCost < theirMarginalCost) {
			lastGuessUseMargin = true;
			toBid += (long) ((theirMarginalCost - ourMarginalCost) * (1 - currentBidMargin));
		}
		
		if (nbTasksHandled > 0) {
			// Check if we would bid too low compared to what the other is normally doing
			Long minBid = 0l;
			for (Long l : theirLastBids) {
				minBid += l;
			}
			minBid = minBid / theirLastBids.size() / 2;
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

		Solution sol = us.computeCentralized(vehicles, tasks);
		System.out.println("Agent: " + agent.name());
		System.out.println("Total reward for agent " + agent.id() + " is : " + ourTotalReward);
		System.out.println("Total cost for agent " + agent.id() + " is : " + sol.getTotalCost());
		System.out.println("Total benefice for agent " + agent.id() + " is : " + (ourTotalReward - sol.getTotalCost()));
		System.out.println();
		
		return createPlanFromSolution(sol);

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

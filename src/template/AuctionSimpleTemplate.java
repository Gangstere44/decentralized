package template;

//the list of imports
import java.util.ArrayList;
import java.util.HashSet;
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
public class AuctionSimpleTemplate implements AuctionBehavior {

	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private City currentCity;
	
	private long totalReward = 0;
	private int INIT_POOL_SIZE = 10;
	private int INIT_MAX_ITER = 10000;
	private Centralized us = new Centralized(INIT_POOL_SIZE, INIT_MAX_ITER);

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

		if (winner == agent.id()) {
			totalReward += bids[agent.id()];
		}
	}

	@Override
	public Long askPrice(Task task) {

		Vehicle closestV = null;
		for (Vehicle v : this.agent.vehicles()) {
			if (closestV == null || closestV.getCurrentCity().distanceTo(task.pickupCity) 
				< v.getCurrentCity().distanceTo(task.pickupCity)) {
				closestV = v;
			}
		}
		
		return (long) ((closestV.getCurrentCity().distanceTo(task.pickupCity) + task.pickupCity.distanceTo(task.deliveryCity)) * closestV.costPerKm());
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

		if (!tasks.isEmpty()) {
			Solution sol = us.computeCentralized(vehicles, tasks);
			System.out.println("Agent: " + agent.name());
			System.out.println("Total reward for agent " + agent.id() + " is : " + totalReward);
			System.out.println("Total cost for agent " + agent.id() + " is : " + sol.getTotalCost());
			System.out.println("Total benefice for agent " + agent.id() + " is : " + (totalReward - sol.getTotalCost()));
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

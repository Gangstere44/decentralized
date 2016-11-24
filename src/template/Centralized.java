package template;

import java.util.HashSet;
import java.util.List;

import logist.simulation.Vehicle;
import logist.task.Task;
import logist.task.TaskSet;

public class Centralized {
	
	private int roundRobin = 0;

	private int poolSize;
	private int maxIter;
	private Solution initSolution = null;

	public Centralized(int poolSize, int maxIter) {
		this.poolSize = poolSize;
		this.maxIter = maxIter;
	}

	public Centralized(int poolSize, int maxIter, Solution initSolution) {
		this.poolSize = poolSize;
		this.maxIter = maxIter;
		this.initSolution = initSolution;
	}
	
	public void setMaxIter(int i) {
		this.maxIter = i;
	}

	public void setInitSolution(Solution is) {
		if(is != null && is.checkCorrectSolution()) {
			initSolution = is.clone();
		} else {
			initSolution = null;
		}
	}

	public Solution computeCentralized(List<Vehicle> vehicles, TaskSet tasks) {

		HashSet<Task> tmp = new HashSet<Task>();
		for(Task t : tasks) {
			tmp.add(t);
		}
		return computeCentralized(vehicles, tmp);
	}

	public Solution computeCentralized(List<Vehicle> vehicles, HashSet<Task> tasks) {
		// Create first solution

		Solution currentSolution = initSolution == null ? createInitSolution(vehicles, tasks) : initSolution;
		Solution bestSolution = currentSolution;

		int iteration = 0;
		int maxIteration = maxIter;

		//Solution[] pool = new Solution[poolSize];
		do {
			iteration++;

			Solution bestRandomN = null;
			int iter = 0;
			while(iter < poolSize || bestRandomN == null) {
				Solution randomN;
				if (currentSolution.canChangeTaskOrder() &&  Math.random() < 0.5) {
					// Change task order
					randomN = changingTaskOrder(currentSolution);
				} else {
					// Change vehicle
					randomN = changingVehicle(currentSolution);
				}

				if(bestRandomN == null || bestRandomN.getTotalCost() > randomN.getTotalCost()) {
					bestRandomN = randomN;
				}
				iter++;
			}

			if (P(currentSolution, bestRandomN, ((double) iteration) / maxIteration) >= Math
					.random()) {
				currentSolution = bestRandomN;
				if (currentSolution.getTotalCost() < bestSolution
						.getTotalCost()) {
					bestSolution = currentSolution;
				}
			}

		} while (iteration < maxIteration);

		/*
		System.out.println("Best solution cost: " + bestSolution.getTotalCost()
				+ ", with iteration " + iteration);
		System.out.println();
		printSolution(bestSolution, true);
		System.out.println();
		 */

		return bestSolution;
	}

	// Acceptance probability function
	// (https://en.wikipedia.org/wiki/Simulated_annealing)
	private double P(Solution currentSolution, Solution newSolution,
			double timeRatio) {

		if (currentSolution.getTotalCost() >= newSolution.getTotalCost()) {
			return 1;
		}

		return (Math.exp(-(newSolution.getTotalCost() - currentSolution
				.getTotalCost()) / timeRatio));

	}

	private Solution changingTaskOrder(Solution oldSolution) {

		Solution toReturn = null;

		while (toReturn == null) {
			int vehicleIdx = (int) (Math.random() * oldSolution.getVehicles()
					.size());
			int firstTaskIdx = (int) (Math.random() * oldSolution
					.getTaskNumber(vehicleIdx));
			int secondTaskIdx = (int) (Math.random() * oldSolution
					.getTaskNumber(vehicleIdx));
			if (firstTaskIdx == secondTaskIdx
					|| oldSolution.getTaskNumber(vehicleIdx) <= 3) {
				continue;
			}

			Solution sol = oldSolution.clone();
			AgentTask firstTask = sol.getAgentTaskAt(vehicleIdx, firstTaskIdx);
			AgentTask secondTask = sol
					.getAgentTaskAt(vehicleIdx, secondTaskIdx);
			if (firstTask == null || secondTask == null) {
				throw new IllegalStateException(
						"New solution does not correspond to the old one.");
			}

			// Exchange
			AgentTask beforeNewOther = secondTask;
			if (!secondTask.equals(firstTask.getNext())) {
				// Get the element before the one we removed
				beforeNewOther = sol.removeTaskForVehicle(vehicleIdx,
						secondTask).get(0);
				sol.addTaskForVehicle(vehicleIdx, secondTask, firstTask);
			}
			sol.removeTaskForVehicle(vehicleIdx, firstTask);
			sol.addTaskForVehicle(vehicleIdx, firstTask, beforeNewOther);

			// Set first of vehicle if needed
			if (sol.checkCorrectSolution()) {
				toReturn = sol;
			}

		}

		return toReturn;
	}

	private Solution changingVehicle(Solution oldSolution) {

		Solution toReturn = null;
		while (toReturn == null) {
			int firstVIdx = (int) (Math.random() * oldSolution.getVehicles()
					.size());
			int secondVIdx = (int) (Math.random() * oldSolution.getVehicles()
					.size());
			int taskIdx = (int) (Math.random() * oldSolution
					.getTaskNumber(firstVIdx));
			if (firstVIdx == secondVIdx
					|| oldSolution.getTaskNumber(firstVIdx) < 2) {
				continue;
			}

			Solution sol = oldSolution.clone();
			AgentTask taskToMove = sol.getAgentTaskAt(firstVIdx, taskIdx);

			sol.removeTaskForVehicle(firstVIdx, taskToMove);
			AgentTask correspondingTask = sol.removeTaskForVehicle(firstVIdx,
					taskToMove.getTask(), !taskToMove.isPickup()).get(1);

			if (taskToMove.isPickup()) {
				sol.addTaskForVehicle(secondVIdx, correspondingTask, null);
				sol.addTaskForVehicle(secondVIdx, taskToMove, null);
			} else {
				sol.addTaskForVehicle(secondVIdx, taskToMove, null);
				sol.addTaskForVehicle(secondVIdx, correspondingTask, null);
			}

			if (sol.checkCorrectSolution()) {
				toReturn = sol;
			}
		}

		return toReturn;
	}

	private Solution createInitSolution(List<Vehicle> vehicles, HashSet<Task> tasks) {
		int vehiclesIdx = roundRobin;
		roundRobin = (roundRobin + 1) % vehicles.size();
		AgentTask[] lastTasks = new AgentTask[vehicles.size()];
		int[] taskCounter = new int[vehicles.size()];
		AgentTask[] vehiclesFirstTask = new AgentTask[vehicles.size()];
		double totalCost = 0.0;

		for (Task task : tasks) {

			Vehicle currentVehicle = vehicles.get(vehiclesIdx);

			AgentTask aTask1 = new AgentTask(task, true);
			AgentTask aTask2 = new AgentTask(task, false);
			aTask1.setNext(aTask2);
			taskCounter[vehiclesIdx] += 2;

			if (currentVehicle.capacity() < task.weight) {
				System.out
						.println("Unsolvable situation: one task is too heavy for one vehicle.");
				return null;
			}

			if (lastTasks[vehiclesIdx] == null) {
				// New vehicle
				// Should we add the cost from current city to first city?
				vehiclesFirstTask[vehiclesIdx] = aTask1;
				totalCost += currentVehicle.getCurrentCity().distanceTo(
						task.pickupCity)
						* currentVehicle.costPerKm();
			} else {
				lastTasks[vehiclesIdx].setNext(aTask1);
				totalCost += (lastTasks[vehiclesIdx].getTask().deliveryCity
						.distanceTo(task.pickupCity))
						* currentVehicle.costPerKm();
			}

			totalCost += (task.pickupCity.distanceTo(task.deliveryCity))
					* currentVehicle.costPerKm();
			lastTasks[vehiclesIdx] = aTask2;
		}

//		System.out.println("Cost of init solution: " + totalCost);

		return new Solution(totalCost, vehiclesFirstTask, vehicles, taskCounter);
	}

	// Helper function
	private void printSolution(Solution sol, boolean id) {
		System.out.println("Solution:");
		for (int i = 0; i < sol.getVehicles().size(); i++) {
			AgentTask current = sol.getVehiclesFirstTask()[i];
			System.out.println("Vehicle " + i + ":");
			while (current != null) {
				System.out.print(current.isPickup() ? "pickup" : "deliver");
				System.out.print(":");
				if (id) {
					if (current.isPickup()) {
						System.out.print(current.getTask().pickupCity + "("
								+ current.getTask().id + ") ; ");
					} else {
						System.out.print(current.getTask().deliveryCity + "("
								+ current.getTask().id + ") ; ");
					}

				} else {
					if (current.isPickup()) {
						System.out.print(current.getTask().pickupCity + "("
								+ current.getTask().weight + ") ; ");
					} else {
						System.out.print(current.getTask().deliveryCity + "("
								+ current.getTask().weight + ") ; ");
					}
				}
				current = current.getNext();
			}
			System.out.println();
		}
	}
}

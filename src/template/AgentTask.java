package template;

import logist.task.Task;

public class AgentTask {

	private Task task;
	private boolean pickup;
	private AgentTask next;

	public AgentTask(Task task, boolean pickup, AgentTask next) {
		this.task = task;
		this.pickup = pickup;
		this.next = next;
	}

	public AgentTask(Task task, boolean pickup) {
		this(task, pickup, null);
	}

	public Task getTask() {
		return this.task;
	}

	public boolean isPickup() {
		return this.pickup;
	}

	public AgentTask getNext() {
		return this.next;
	}

	public void setNext(AgentTask n) {
		this.next = n;
	}

}

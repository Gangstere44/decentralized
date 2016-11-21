package template;

public class Pair<K, V> {

	private K elem1;
	private V elem2;

	public Pair(K e1, V e2) {
		elem1 = e1;
		elem2 = e2;
	}

	@Override
	public boolean equals(Object obj) {

		Pair<K, V> o = (Pair<K, V>) obj;

		return this.elem1.equals(o.elem1) && this.elem2.equals(o.elem2);
	}

	@Override
	public int hashCode() {
		return elem1.hashCode() + 11 * elem2.hashCode();
	}
}

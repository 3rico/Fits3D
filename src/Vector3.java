
public class Vector3 {
	public static final Vector3 zeros = 	new Vector3( 0f, 0f, 0f);
	public static final Vector3 ones = 		new Vector3( 1f, 1f, 1f);
	public static final Vector3 right = 	new Vector3( 1f, 0f, 0f);
	public static final Vector3 left = 		new Vector3(-1f, 0f, 0f);
	public static final Vector3 up = 		new Vector3( 0f, 1f, 0f);
	public static final Vector3 down = 		new Vector3( 0f,-1f, 0f);
	public static final Vector3 in = 		new Vector3( 0f, 0f, 1f);
	public static final Vector3 out = 		new Vector3( 0f, 0f,-1f);
	
	public final float x;
	public final float y;
	public final float z;
	
	public Vector3(float x, float y, float z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}
	
}

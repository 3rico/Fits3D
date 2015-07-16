import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.Future;

import javax.swing.JOptionPane;


import com.sun.corba.se.impl.orbutil.closure.*;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.ImageHDU;
import nom.tam.fits.header.IFitsHeader.HDU;
import nom.tam.util.ArrayDataInput;

/**
 * A data representation of a region at some fidelity
 * @author chrishawkins
 *
 */
public class RegionRepresentation {
	public int[]buckets;
	public float estMin;
	public float estMax;

	public int numPtsX;
	public int numPtsY;
	public int numPtsZ;
	public int validPts;
	public boolean isMaximumFidelity;
	public float fidelity;

	private List<VertexBufferSlice>slices;

	/**
	 * 
	 * @param fileName Filename of the original fits file
	 * @param fidelity Fidelity of the represenation (1 being perfect, 0 being emtpy)
	 * @param volume a volume cube indicating the area of the data to sample (full sample is == new volume(0,0,0,1,1,1));
	 */
	public enum DataType {
		FLOAT, DOUBLE, SHORT, INT, LONG,
	}
	private RegionRepresentation() {

	}


	/**
	 *
	 * @param fits	The fits file to read.
	 * @param fidelity The level of fidelity to read the cube at (note fidelity is interpreted cubically so 1 is 8 times larger than 0.5)
	 * @param volume The unit volume of the fits file to read.
	 * @return A representation of the asked for region.
	 */
	public static RegionRepresentation justTheSlicesPlease(Fits fits, float fidelity, Volume volume) {
		RegionRepresentation rr = new RegionRepresentation();
		rr.fidelity = fidelity;
		long t0 = System.currentTimeMillis();
		try {
			ImageHDU hdu = (ImageHDU) fits.getHDU(0);
			MinAndMax minAndMax = minAndMaxBasedOnRoughOnePercentRushThrough(hdu, volume, fits);
			rr.estMax = minAndMax.max;
			rr.estMin = minAndMax.min;

			int sourceWidth = hdu.getAxes()[0];											//the length of the cube in points
			int sourceHeight = hdu.getAxes()[1];											//the height of the cube in points
			int sourceDepth = hdu.getAxes().length > 2? hdu.getAxes()[2] : 1;			//the depth of the cube in points

			int stride = (int)(1.0f/fidelity);												//how many to step in each direction

			int sourceStartX = (int)(volume.x * sourceWidth);							//the first point number x to read
			int sourceStartY = (int)(volume.y * sourceHeight);							//the first point number y to read
			int sourceStartZ = (int)(volume.z * sourceDepth);							//the first point number z to read

			int sourceEndX = (int)((volume.x + volume.wd) * sourceWidth);				//the last point number x to read
			int sourceEndY = (int)((volume.y + volume.ht) * sourceHeight);				//the last point number y to read
			int sourceEndZ = (int)((volume.z + volume.dp) * sourceDepth);				//the last point number z to read

			int repWidth = (sourceEndX - sourceStartX)/stride;
			int repHeight = (sourceEndY - sourceStartY)/stride;
			int repDepth = (sourceEndZ - sourceStartZ)/stride;

			rr.numPtsX = repWidth;
			rr.numPtsY = repHeight;
			rr.numPtsZ = repDepth;

			int yRemainder = sourceHeight - stride*(sourceHeight/stride);

			DataType dataType;
			int bitPix = hdu.getBitPix();
			int typeSize = Math.abs(bitPix)/8;
			float[] storagef = null;
			double[] storaged = null;
			switch (bitPix) {
				case -64:
					dataType = DataType.DOUBLE;
					storaged = new double[sourceDepth];
					break;
				case -32:
					dataType = DataType.FLOAT;
					storagef = new float[sourceDepth];
					break;
				default:
					throw new IOException("Whoops, no support forthat file format (BitPix = "+bitPix+") at the moment.  Floats and Doubles only sorry.");
			}

			int numNeg = 0;
			int numTot = 0;
			int nBuckets = 100;
			rr.buckets = new int[nBuckets];
			float min = minAndMax.min;
			float max = minAndMax.max;
			float stepSize = (max - min) / (float)nBuckets;

			float minn = 999f;
			float maxx = -999f;

			rr.slices = new ArrayList<>();

			Random r = new Random(1);
			float xStride = 1.0f/(float)repWidth;
			float yStride = 1.0f/(float)repHeight;
			float zStride = 1.0f/(float)repDepth;
			if (hdu.getData().reset()) {
				ArrayDataInput adi = fits.getStream();
				int planesToSkip = sourceStartX;
				adi.skipBytes(sourceDepth * sourceHeight * planesToSkip * typeSize);

				for (int x = 0; x < repWidth; x ++) {
					float xProportion = (float)x/(float)repWidth;
					int pts = 0;
					int maxPts = repHeight * repDepth;

					ShortBuffer vertexBuffer = ShortBuffer.allocate(maxPts * 3);
					FloatBuffer valueBuffer = FloatBuffer.allocate(maxPts);

					for (int y = 0; y < repHeight; y ++) {
						float yProportion = (float)y/(float)repHeight;

						if (dataType == DataType.DOUBLE)
							adi.read(storaged, 0, storaged.length);
						else if (dataType == DataType.FLOAT)
							adi.read(storagef, 0, storagef.length);

						for (int z = 0; z < repDepth; z++) {
							float zProportion = (float)z/(float)repDepth;

							float val;
							if (dataType == DataType.DOUBLE) {
								val = (float)storaged[z * stride];
							} else {
								val = storagef[z * stride];
							}
							int bucketIndex = (int)(val - rr.estMin/stepSize);
							if (bucketIndex >= 0 && bucketIndex < nBuckets && !Double.isNaN(val)){
								rr.buckets[bucketIndex]++;

								float fudge = r.nextFloat();
								fudge = fudge - 0.5f;

								vertexBuffer.put((short) ((xProportion + fudge * xStride) * Short.MAX_VALUE));
								vertexBuffer.put((short) ((yProportion + fudge * yStride) * Short.MAX_VALUE));
								vertexBuffer.put((short) ((zProportion + fudge * zStride) * Short.MAX_VALUE));
								valueBuffer.put(val);
								pts++;
							}
						}

						if (y == repHeight-1 && yRemainder!=0) {
							//is remainder zone
							int linesToSkip = yRemainder + stride - 1;
							adi.skipBytes(sourceDepth * linesToSkip * typeSize );
						} else {
							int linesToSkip = stride - 1;
							adi.skipBytes(sourceDepth * linesToSkip * typeSize);
						}
					}

					//--make a vbo slice
					vertexBuffer.flip();
					valueBuffer.flip();

					VertexBufferSlice vbs = new VertexBufferSlice();
					vbs.vertexBuffer = vertexBuffer;
					vbs.valueBuffer = valueBuffer;
					vbs.numberOfPts = pts;
					vbs.depthValue = xProportion;
					rr.slices.add(vbs);

					//--skip to the next slice
					adi.skipBytes(sourceDepth * sourceHeight * (stride - 1) * typeSize);
				}
			}

			System.out.println("fits file loaded " + repWidth + " x " + repHeight + " x " + repDepth);

		}catch (Exception e) {
			JOptionPane.showMessageDialog(null, e.getClass().getName()+": " + e.getMessage(), "Error!", JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
		}

		long t1 = System.currentTimeMillis();
		System.out.println("time taken to load file in new fancy way:" + (t1 - t0));
		return rr;
	}

	public static float toFloat( int hbits )
	{
		int mant = hbits & 0x03ff;            // 10 bits mantissa
		int exp =  hbits & 0x7c00;            // 5 bits exponent
		if( exp == 0x7c00 )                   // NaN/Inf
			exp = 0x3fc00;                    // -> NaN/Inf
		else if( exp != 0 )                   // normalized value
		{
			exp += 0x1c000;                   // exp - 15 + 127
			if( mant == 0 && exp > 0x1c400 )  // smooth transition
				return Float.intBitsToFloat( ( hbits & 0x8000 ) << 16
						| exp << 13 | 0x3ff );
		}
		else if( mant != 0 )                  // && exp==0 -> subnormal
		{
			exp = 0x1c400;                    // make it normal
			do {
				mant <<= 1;                   // mantissa * 2
				exp -= 0x400;                 // decrease exp by 1
			} while( ( mant & 0x400 ) == 0 ); // while not normal
			mant &= 0x3ff;                    // discard subnormal bit
		}                                     // else +/-0 -> +/-0
		return Float.intBitsToFloat(          // combine all parts
				( hbits & 0x8000 ) << 16          // sign  << ( 31 - 15 )
						| ( exp | mant ) << 13 );         // value << ( 23 - 10 )
	}

	private static class MinAndMax{
		float min,max;
	}

	public void clear() {
		slices = null;
	}

	private MinAndMax minAndMaxBasedOnHeader(ImageHDU hdu) {
		double max = hdu.getMaximumValue();
		double min = hdu.getMinimumValue();
		MinAndMax mam = new MinAndMax();
		mam.min = (float)min;
		mam.max = (float)max;
		return mam;
	}

	public List<VertexBufferSlice> getSlices() {
		return this.slices;
	}

	private static MinAndMax minAndMaxBasedOnRoughOnePercentRushThrough(ImageHDU hdu, Volume volume, Fits fits) {
		MinAndMax mam = new MinAndMax();

		long t0 = System.currentTimeMillis();
		float minn = 999f;
		float maxx = -999f;

		List<Float>allOfThemFloats =new ArrayList<Float>();
		try{
			float shittyFidelity = 0.1f;

			int sourceMaxWidth = hdu.getAxes()[0];
			int sourceMaxHeight = hdu.getAxes()[1];
			int sourceMaxDepth = hdu.getAxes().length > 2? hdu.getAxes()[2] : 0;

			//stirde of 1 = full fidelity , stride of 2 = half fidelity
			int stride = (int)(1.0f/shittyFidelity);
			System.out.println("stride:"+stride);


			int sourceStartX = (int)(volume.x * sourceMaxWidth);
			int sourceStartY = (int)(volume.y * sourceMaxHeight);
			int sourceStartZ = (int)(volume.z * sourceMaxDepth);

			int sourceEndX = (int)((volume.x + volume.wd) * sourceMaxWidth);
			int sourceEndY = (int)((volume.y + volume.ht) * sourceMaxHeight);
			int sourceEndZ = (int)((volume.z + volume.dp) * sourceMaxDepth);

			int maxWidth = (sourceEndX - sourceStartX)/stride;
			int maxHeight = (sourceEndY - sourceStartY)/stride;
			int maxDepth = (sourceEndZ - sourceStartZ)/stride;

			int numPtsX = maxWidth;
			int numPtsY = maxHeight;
			int numPtsZ = maxDepth;

			int yRemainder = sourceMaxHeight - stride*(sourceMaxHeight/stride);

			DataType dataType;
			int bitPix = hdu.getBitPix();
			int typeSize = Math.abs(bitPix)/8;
			float[] storagef = null;
			double[] storaged = null;
			switch (bitPix) {
				case -64:
					dataType = DataType.DOUBLE;
					storaged = new double[sourceMaxDepth];
					break;
				case -32:
					dataType = DataType.FLOAT;
					storagef = new float[sourceMaxDepth];
					break;
				default:
					throw new IOException("Whoops, no support forthat file format (BitPix = "+bitPix+") at the moment.  Floats and Doubles only sorry.");
			}


			if (hdu.getData().reset()) {

				ArrayDataInput adi = fits.getStream();
				int planesToSkip = sourceStartX;
				adi.skipBytes(sourceMaxDepth * sourceMaxHeight * planesToSkip * typeSize);

				for (int x = 0; x < maxWidth; x ++) {
					for (int y = 0; y < maxHeight; y ++) {
						if (dataType == DataType.DOUBLE) {
							adi.read(storaged, 0, storaged.length);
							for (int z = 0; z < maxDepth; z++) {
								float val = (float)storaged[z * stride];
								if (Double.isNaN(val))
									continue;
								if (val < minn) minn = val;
								if (val > maxx) maxx = val;

								allOfThemFloats.add(val);
							}
						}
						else if (dataType == DataType.FLOAT) {
							adi.read(storagef, 0, storagef.length);
							for (int z = 0; z < maxDepth; z++) {
								float val = (float)storagef[z * stride];
								if (Float.isNaN(val))
									continue;
								if (val < minn) minn = val;
								if (val > maxx) maxx = val;
								allOfThemFloats.add(val);
							}
						}

						if (y == maxHeight-1 && yRemainder!=0) {
							//is remainder zone
							int linesToSkip = yRemainder + stride - 1;
							adi.skipBytes(sourceMaxDepth * linesToSkip * typeSize );
						} else {
							int linesToSkip = stride - 1;
							adi.skipBytes(sourceMaxDepth * linesToSkip * typeSize);
						}
					}
					adi.skipBytes(sourceMaxDepth * sourceMaxHeight * (stride - 1) * typeSize);
				}
			}

			System.out.println("fits file loaded " + maxWidth + " x " + maxHeight + " x " + maxDepth);
			System.out.println("min" + minn);
			System.out.println("max" + maxx);
			allOfThemFloats.sort(new Comparator<Float>() {
				@Override
				public int compare(Float o1, Float o2) {
					return Float.compare(o1.floatValue(), o2.floatValue());
				}
			});


		}catch(Exception e){
			e.printStackTrace();
		}
		System.out.println("time taken to estimate min and max :" + (System.currentTimeMillis() - t0) + "ms");
		mam.min = minn;
		mam.max = maxx;
		return mam;
	}

}
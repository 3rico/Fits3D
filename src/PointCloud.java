import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PointCloud implements  AttributeProvider {

	private final static String[] 	AXES_NAMES 			= {"X", "Y", "Z"};
	private final static Color[] 	DEFAULT_COLORS 		= {Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE, Color.PINK};
	private final static float 		STARTING_FIDELITY 	= 0.075f;

	private final static float 		BOX_WIDTH			= 2.0f;
	private final static float 		BOX_HEIGHT 			= BOX_WIDTH;
	private final static float 		BOX_DEPTH 			= BOX_WIDTH;

	private final static float		BOX_ORIGIN_X 		= -0.5f * BOX_WIDTH;
	private final static float 		BOX_ORIGIN_Y 		= -0.5f * BOX_HEIGHT;
	private final static float 		BOX_ORIGIN_Z 		= -0.5f * BOX_DEPTH;
	private Fits fits;

	private static int clouds = 0;


	public Volume volume;
	private Volume backupVolume;
	private Volume galacticVolume;
	
	public final Color color;
	
	private List<Region>regions;
	
	private List<Attribute>attributes = new ArrayList<Attribute>();

	//--interactive attributes
	public Attribute.RangedAttribute intensity;
	public Attribute.BinaryAttribute isVisible;
	public Attribute.SteppedRangeAttribute quality;
	public Attribute.FilterSelectionAttribute filterSelection;
	public Attribute.BinaryAttribute isSelected;
	public Attribute.MultiChoiceAttribute relativeTo;

	public Attribute.TextAttribute[] unitTypes;
	//--static attributes
	public Attribute.TextAttribute fileName;

	public PointCloud(String pathName) {
		this.regions = new ArrayList<Region>();
		this.volume = new Volume(BOX_ORIGIN_X, BOX_ORIGIN_Y, BOX_ORIGIN_Z, BOX_WIDTH, BOX_HEIGHT, BOX_DEPTH);
		this.backupVolume = this.volume;
		fileName = new Attribute.PathName("Filename", pathName, false);
		attributes.add(fileName);

		intensity = new Attribute.RangedAttribute("Visibility", 0.001f, 1f, 0.5f, false);
		attributes.add(intensity);

		quality = new Attribute.SteppedRangeAttribute("Quality", 0.1f, 1.0f, STARTING_FIDELITY, 10, true);
		quality.callback = (obj) -> {
			float newQuality = ((Float) obj).floatValue();
			System.out.println("quality is now :" + newQuality);

			Runnable r = new Runnable() {
				public void run() {
					//--TODO don't hack this maybe
					Region primaryRegion = PointCloud.this.regions.get(0);
					List<Region> children = primaryRegion.getMinusRegions();
					primaryRegion.setMinusRegions(new ArrayList<>());
					primaryRegion.loadRepresentationAtFidelity(newQuality);
					for (Region child : children) {
						child.populateAsSubregion(primaryRegion, newQuality, true);
					}

					for (Region region : PointCloud.this.regions) {
						region.quality.notifyWithValue(newQuality, false);
					}


					primaryRegion.setMinusRegions(children);
					FrameMaster.setNeedsNewRenderer();
					FrameMaster.setNeedsDisplay();
//					FrameMaster.setNeedsAttributesReload();
				}
			};
			new Thread(r).start();
		};
		attributes.add(quality);

		isVisible = new Attribute.BinaryAttribute("Visible", true, true);

		attributes.add(isVisible);

		isSelected = new Attribute.BinaryAttribute("Selected", false, true);
		attributes.add(isSelected);

		Christogram.Filter data = new Christogram.Filter(0f, 1f, 0f, 1f, false);
		filterSelection = new Attribute.FilterSelectionAttribute("Filter", false, data);
		attributes.add(filterSelection);

		List<Object> possiblePairings = new ArrayList<>();
		possiblePairings.add("-");
		relativeTo = new Attribute.MultiChoiceAttribute("Relative to", possiblePairings, possiblePairings.get(0));
		relativeTo.callback = (obj) -> {
			if (obj instanceof PointCloud) {
				PointCloud parent = (PointCloud) obj;
				this.setVolume(this.volumeNormalisedToParent(parent));
			} else {
				this.setVolume(this.backupVolume);
			}
		};

		attributes.add(relativeTo);

		this.color = DEFAULT_COLORS[clouds++ % DEFAULT_COLORS.length];
	}

	public void readFits() {
		readFitsAtQualityLevel(this.quality.getValue());
	}

	private void readFitsAtQualityLevel(float proportionOfPerfect) {
		try{
			this.fits = new Fits(this.fileName.getValue());

			ImageHDU hdu = (ImageHDU)this.fits.getHDU(0);
			attributes.add(0,new Attribute.TextAttribute("Data Type", BitPix.dataTypeForBitPix(hdu.getBitPix()).name(), false));
			attributes.add(1,new Attribute.TextAttribute("Observer", hdu.getObserver(), false));
			attributes.add(2, new Attribute.TextAttribute("Observed", "" + hdu.getObservationDate(), false));

			this.unitTypes = new Attribute.TextAttribute[3];
			for (int i = 0; i < 3; i++) {
				Attribute.TextAttribute unitAttribute = new Attribute.TextAttribute("Unit "+ AXES_NAMES[i], "" + hdu.getHeader().getStringValue("CTYPE"+(i+1)), false);
				this.unitTypes[i] = unitAttribute;
				attributes.add(attributes.size() - 1, unitAttribute);
			}
			Attribute.TextAttribute unitAttribute = new Attribute.TextAttribute("Unit Z", "" + hdu.getHeader().getStringValue("BUNIT"), false);
			attributes.add(attributes.size() - 1, unitAttribute);

			for (int i = hdu.getAxes().length-1; i >= 0 ; i--) {
				attributes.add(attributes.size() - 1,new Attribute.TextAttribute(AXES_NAMES[i] + " Resolution", "" + hdu.getAxes()[i], false));
			}

			attributes.add(attributes.size() - 1, new Attribute.TextAttribute("Instrument", hdu.getInstrument(), false));
			for (Attribute attr : attributes) {
				if (attr instanceof Attribute.TextAttribute) {
					Attribute.TextAttribute namedAttr = (Attribute.TextAttribute)attr;
					if (namedAttr.getValue() == null || namedAttr.getValue().equals("") || namedAttr.getValue().equals("null"))
						namedAttr.notifyWithValue("?");
				}
			}

			//--figure out the reference position

			Header header = hdu.getHeader();
			float[] pixels = new float[3];
			float[] values = new float[3];
			float[] sizes = new float[3];
			for (int naxis = 1; naxis <= hdu.getAxes().length; naxis++) {
				pixels[naxis - 1] = header.getFloatValue("CRPIX"+naxis)/ (float) hdu.getAxes()[naxis - 1];
				sizes[naxis - 1] = header.getFloatValue("CDELT"+naxis) * (float) hdu.getAxes()[naxis - 1];
				values[naxis - 1] = header.getFloatValue("CRVAL"+naxis);
			}

			Vector3 refPixel = new Vector3(pixels);
			Vector3 refCoordinate = new Vector3(values);
			Vector3 size = new Vector3(sizes);

			Vector3 realOrigin = refCoordinate.minus(refPixel.scale(size));

			this.galacticVolume = new Volume(realOrigin, size);

			Volume v = new Volume(0f,0f,0f,1f,1f,1f);
			Region region = new Region(fits, v, proportionOfPerfect);
			this.addRegion(region);

			FrameMaster.setNeedsNewRenderer();
			FrameMaster.setNeedsDisplay();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	/**
	 * All the slices that compose this point cloud.  This includes from all the contained regions.  There are no guarancees concerning order.
	 * @return The list of all slices composing the point cloud
	 */
	public List<VertexBufferSlice>getSlices() {
		List<VertexBufferSlice>slices = new ArrayList<VertexBufferSlice>();
		for (Region region:this.regions) {
			slices.addAll(region.getSlices());
		}
		return slices;
	}


	private void addRegion (Region cr) {
		this.regions.add(cr);

		for (Region region : this.regions) {
			List<Region>chrilden = new ArrayList<>();
			for (Region potentialChild : this.regions) {
				Vector3 origin = potentialChild.getVolume().origin;
				Vector3 extremety = origin.add(potentialChild.getVolume().size);
				boolean containsOrigin = region.getVolume().containsPoint(origin);
				boolean containsExtremety = region.getVolume().containsPoint(extremety);
				boolean isNotParadox = region != potentialChild;
				if (containsOrigin && containsExtremety && isNotParadox) {
					chrilden.add(potentialChild);
				}
			}
			region.setMinusRegions(chrilden);
		}
	}


	/**
	 * Cuts out a volume from the primary region.  The cut out region is kept in the point cloud as a new region.
	 * @param volume The volume to cut out (in world space)
	 */
	public void cutOutSubvolume(Volume volume) {
		Volume subRegion = this.volume.normalisedProportionVolume(volume);
		Region newRegion = this.regions.get(0).subRegion(subRegion, this.regions.get(0).getRegionRepresentation().getFidelity(), true);
		this.addRegion(newRegion);
		FrameMaster.notifyFileBrowserOfNewRegion(this, newRegion);
		FrameMaster.setNeedsNewRenderer();
		FrameMaster.setNeedsDisplay();

	}




	/**
	 * Get all the children of this point cloud that could provide their own attribuets (eg. Regions)
	 * @return The list of all children that provide attributes
	 */
	public List<AttributeProvider> getChildProviders() {
		List<AttributeProvider>attributeProviders = new ArrayList<>(this.regions);
		return attributeProviders;
	}


	/**
	 * Returns a volume that represents this point cloud scaled down and position relative to the supplied parent.
	 * @param parent The parent cloud to position and scale the volume relative to
	 * @return The scaled and positioned volume that is relative to the parent
	 */
	public Volume volumeNormalisedToParent(PointCloud parent) {
		Volume parGalVol = parent.galacticVolume;
		Volume parVol = parent.volume;
		Volume galVol = this.galacticVolume;

		Vector3 realDist = galVol.origin.minus(parGalVol.origin);

		float[] realToDisplayFactorArray = new float[3];
		for (int i = 0; i < 3; i++) {
			realToDisplayFactorArray[i] = parVol.size.get(i)/ parGalVol.size.get(i);
		}
		Vector3 realToDisplayFactor = new Vector3(realToDisplayFactorArray);

		Vector3 displayOrigin = parVol.origin.add(realDist.scale(realToDisplayFactor));
		Vector3 displaySize = galVol.size.scale(realToDisplayFactor);

		//--ok so heres what's going to happen if the units don't match that of the real cube then it will just match the parent cube in that dimension.  So if none of the dimensions match both cubes should perfectly sit on top of each other.
		for (int i = 0; i < 3; i++) {
			if (this.unitTypes[i].getValue().equals(parent.unitTypes[i].getValue()) == false) {
				System.err.println("Cannot position clouds relative in "+ AXES_NAMES[i]+" axis as there is a unit mismatch ("+this.unitTypes[i].getValue() +" != " + parent.unitTypes[i].getValue()+")");
				float []newOriginValues = displayOrigin.toArray();
				newOriginValues[i] = parVol.origin.get(i);
				displayOrigin = new Vector3(newOriginValues);
			}
		}

		Volume newDisplayVolume = new Volume(displayOrigin, displaySize);

		return newDisplayVolume;
	}


	public String toString() {
		String[] components = this.fileName.getValue().split(File.separator);
		return components[components.length - 1];
	}






	//==================================================================================================================
	//  HISTOGRAM
	//==================================================================================================================

	public int[] getHistBuckets() {
		return this.regions.get(0).getRegionRepresentation().getBuckets();
	}


	public float getHistMin() {
		return this.regions.get(0).getRegionRepresentation().getEstMin();
	}


	public float getHistMax() {
		return this.regions.get(0).getRegionRepresentation().getEstMax();
	}


	public Christogram.Filter getFilter() {
		return this.filterSelection.getValue();
	}






	//==================================================================================================================
	//  GETTERS + SETTERS
	//==================================================================================================================

	public Volume getVolume() {
		return volume;
	}


	public void setVolume(Volume volume) {
		this.volume = volume;
	}


	@Override
	public List<Attribute> getAttributes() {
		List<Attribute>visibleAttributes = new ArrayList<>();
		visibleAttributes.addAll(this.attributes);
		return visibleAttributes;
	}


	public List<Region> getRegions() {
		return regions;
	}
}

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;

import java.awt.Color;
import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class PointCloud implements  AttributeProvider {
	private final static String[] axesNames = {"X", "Y", "Z"};
	private final static Color[] colors = {Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE, Color.PINK};
	public final static float startingFidelity = 0.075f;

	private static int clouds = 0;

	public Region pendingRegion;

	private Fits fits;

	private final float boxWidth = 2.0f;
	private final float boxHeight = boxWidth;
	private final float boxDepth = boxWidth;

	private final float boxOrigZ = -0.5f * boxDepth;
	private final float boxOrigX = -0.5f * boxWidth;
	private final float boxOrigY = -0.5f * boxHeight;

	public Volume volume;
	private Volume backupVolume;
	private Volume galacticVolume;
	
	public final Color color;
	
	List<Region>regions;
	
	private List<Attribute>attributes = new ArrayList<Attribute>();

	//--interactive attributes
	public Attribute.RangedAttribute intensity;
	public Attribute.BinaryAttribute isVisible;
	public Attribute.SteppedRangeAttribute quality;
	private Attribute.FilterSelectionAttribute filterSelection;
	public Attribute.BinaryAttribute isSelected;
	public Attribute.MultiChoiceAttribute relativeTo;

	public Attribute.TextAttribute[] unitTypes;
	//--static attributes
	public Attribute.TextAttribute fileName;

	public PointCloud(String pathName) {
		this.regions = new ArrayList<Region>();
		this.volume = new Volume(boxOrigX, boxOrigY, boxOrigZ, boxWidth, boxHeight, boxDepth);
		this.backupVolume = this.volume;
		fileName = new Attribute.PathName("Filename", pathName, false);
		attributes.add(fileName);
		
		intensity = new Attribute.RangedAttribute("Visibility", 0.001f, 1f, 0.5f, false);
		attributes.add(intensity);
		
		quality = new Attribute.SteppedRangeAttribute("Quality", 0.1f, 1.0f, startingFidelity, 10, true);
		quality.callback = (obj) -> {
			float newQuality = ((Float)obj).floatValue();
			System.out.println("quality is now :" + newQuality);

			Runnable r = new Runnable() {
				public void run() {
					//--TODO don't hack this maybe
					Region primaryRegion = PointCloud.this.regions.get(0);
					List<Region>children = primaryRegion.getMinusRegions();
					primaryRegion.setMinusRegions(new ArrayList<>());
					primaryRegion.getMeMyRepresentation(newQuality);
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

		Christogram.Filter data = new Christogram.Filter(0f,1f,0f,1f,false);
		filterSelection = new Attribute.FilterSelectionAttribute("Filter", false, data);
		attributes.add(filterSelection);

		List<Object> possiblePairings = new ArrayList<>();
		possiblePairings.add("-");
		relativeTo = new Attribute.MultiChoiceAttribute("Relative to", possiblePairings, possiblePairings.get(0));
		relativeTo.callback = (obj) -> {
			if (obj instanceof  PointCloud) {
				PointCloud parent = (PointCloud)obj;
				this.setVolume(this.volumeNormalisedToParent(parent));
			}
			else {
				this.setVolume(this.backupVolume);
			}
		};

		attributes.add(relativeTo);

		this.color = colors[clouds++ % colors.length];
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
				Attribute.TextAttribute unitAttribute = new Attribute.TextAttribute("Unit "+axesNames[i], "" + hdu.getHeader().getStringValue("CTYPE"+(i+1)), false);
				this.unitTypes[i] = unitAttribute;
				attributes.add(attributes.size() - 1, unitAttribute);
			}
			Attribute.TextAttribute unitAttribute = new Attribute.TextAttribute("Unit Z", "" + hdu.getHeader().getStringValue("BUNIT"), false);
			attributes.add(attributes.size() - 1, unitAttribute);

			for (int i = hdu.getAxes().length-1; i >= 0 ; i--) {
				attributes.add(attributes.size() - 1,new Attribute.TextAttribute(axesNames[i] + " Resolution", "" + hdu.getAxes()[i], false));
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
			this.addRegion(region, this.regions);

			FrameMaster.setNeedsNewRenderer();
			FrameMaster.setNeedsDisplay();
//			loadRegionAtFidelity(proportionOfPerfect);
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	static int c = 0;
//	public void loadRegionAtFidelity(float fidelity) {
//		Region region;
//		if (regions.size() == 0) {
//			Volume v = new Volume(0f,0f,0f,1f,1f,1f);
//			region = new Region(fits, v, fidelity);
//
//
//			regions.add(region);
//		}else {
//			int rindex = 0;
//			region = regions.get(rindex);
//			Volume v = region.volume;
//
//			//--if you're the top dog then clear out the children as well
//			List<Region>chrilden = new ArrayList<>();
//			for (Region potentialChild : this.regions) {
//				Vector3 origin    = potentialChild.volume.origin;
//				Vector3 extremety = origin.add(potentialChild.volume.size);
//				boolean containsOrigin = region.volume.containsPoint(origin);
//				boolean containsExtremety = region.volume.containsPoint(extremety);
//				boolean isNotParadox = region != potentialChild;
//				if (containsOrigin && containsExtremety && isNotParadox) {
//					chrilden.add(potentialChild);
//				}
//			}
//			this.regions.set(rindex, new Region(fits, v, fidelity, chrilden));
//
//			//--change the name of the original region
//		}
//
////		region.setName("Region"+regions.size());
//		FrameMaster.setNeedsNewRenderer();
//		FrameMaster.setNeedsDisplay();
//	}
	
	public List<Region> getRegions() {
		return regions;
	}
	
	public List<VertexBufferSlice>getSlices() {
		List<VertexBufferSlice>slices = new ArrayList<VertexBufferSlice>();
		for (Region region:this.regions) {
			slices.addAll(region.getSlices());
		}
		return slices;
	}
	
	public void addRegion (Region cr, List<Region>existingRegions) {
		existingRegions.add(cr);

		for (Region region : this.regions) {
			List<Region>chrilden = new ArrayList<>();
			for (Region potentialChild : this.regions) {
				Vector3 origin = potentialChild.volume.origin;
				Vector3 extremety = origin.add(potentialChild.volume.size);
				boolean containsOrigin = region.volume.containsPoint(origin);
				boolean containsExtremety = region.volume.containsPoint(extremety);
				boolean isNotParadox = region != potentialChild;
				if (containsOrigin && containsExtremety && isNotParadox) {
					chrilden.add(potentialChild);
				}
			}
			region.setMinusRegions(chrilden);
		}
//		class RegionOrderer implements Comparator<Region> {
//			public int compare(Region a, Region b) {
//				return a.depth < b.depth ? -1 : 1;
//			}
//		}
//		Collections.sort(existingRegions, new RegionOrderer());
	}

	public void clearRegions() {
		for (Region region : regions) {
			region.clear();
		}
		regions.clear();
	}

	public void makeSomeStupidSubregion() {
		Volume corner = new Volume(0.25f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f);
		Region newRegion = this.regions.get(0).subRegion(corner, this.regions.get(0).regionRepresentation.fidelity, true);
		this.pendingRegion = newRegion;
		FrameMaster.setNeedsDisplay();
	}

	public void makeSomeStupidOtherSubregion(Volume volume) {
		Volume subRegion = this.volume.normalisedProportionVolume(volume);
		Region newRegion = this.regions.get(0).subRegion(subRegion, this.regions.get(0).regionRepresentation.fidelity, true);
		this.pendingRegion = newRegion;
		FrameMaster.setNeedsDisplay();
	}

	public void blastVolumeWithQuality(Volume volume) {
		Volume subRegion = this.volume.normalisedProportionVolume(volume);
		Region newRegion = this.regions.get(0).subRegion(subRegion, 1.0f, true);
		this.pendingRegion = newRegion;
		FrameMaster.setNeedsDisplay();
	}

	public String toString() {
		String[] components = this.fileName.getValue().split(File.separator);
		return components[components.length - 1];
	}
	
	public int[] getHistBuckets() {
		return this.regions.get(0).regionRepresentation.buckets;
	}

	public float getHistMin() {
		return this.regions.get(0).regionRepresentation.estMin;
	}

	public float getHistMax() {
		return this.regions.get(0).regionRepresentation.estMax;
	}

	public Christogram.Filter getFilter() {
		return this.filterSelection.getValue();
	}



	@Override
	public List<Attribute> getAttributes() {
		List<Attribute>visibleAttributes = new ArrayList<>();
		visibleAttributes.addAll(this.attributes);

		return visibleAttributes;
	}

	public List<AttributeProvider> getChildProviders() {
		List<AttributeProvider>attributeProviders = new ArrayList<>(this.regions);
		return attributeProviders;
	}

	public Volume volumeNormalisedToParent(PointCloud parent) {
		Volume parGalVol = parent.galacticVolume;
		Volume parVol = parent.volume;
		Volume galVol = this.galacticVolume;
		Volume vol = this.volume;

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
				System.err.println("Cannot position clouds relative in "+axesNames[i]+" axis as there is a unit mismatch ("+this.unitTypes[i].getValue() +" != " + parent.unitTypes[i].getValue()+")");
				float []newOriginValues = displayOrigin.toArray();
				newOriginValues[i] = parVol.origin.get(i);
				displayOrigin = new Vector3(newOriginValues);
			}
		}



		Volume newDisplayVolume = new Volume(displayOrigin, displaySize);

		return newDisplayVolume;
	}


	public Volume getVolume() {
		return volume;
	}

	public void setVolume(Volume volume) {
		this.volume = volume;
	}
}

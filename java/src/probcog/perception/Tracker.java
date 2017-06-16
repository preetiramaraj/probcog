package probcog.perception;

import java.awt.Color;
import java.io.*;
import java.util.*;

import april.config.*;
import april.jmat.*;
import april.jmat.geom.*;
import april.sim.*;
import april.util.*;
import april.vis.*;
import probcog.classify.*;
import probcog.classify.Features.FeatureCategory;
import probcog.sensor.*;
import probcog.sim.ISimEffector;
import probcog.sim.ISimStateful;
import probcog.sim.SimFlatSurface;
import probcog.sim.SimLocation;
import probcog.sim.SimObjectPC;
import probcog.util.*;
import probcog.rosie.perception.*;

import edu.wpi.rail.jrosbridge.*;
import edu.wpi.rail.jrosbridge.messages.*;
import edu.wpi.rail.jrosbridge.callback.*;
import javax.json.*;

public class Tracker
{
    private Ros ros;

    // Classification stuff
    ClassifierManager classyManager;

    // Soar stuff
    private Object soarLock;
    private HashMap<Integer, Obj> soarObjects;

    private Object worldLock = new Object();

    private Segmenter segmenter;

    // World state, as decided by Soar and the perception system
    private SimWorld world;
    public Object stateLock;
    HashMap<Integer, Obj> worldState;

    public double fps = 0;
    ArrayList<Double> frameTimes = new ArrayList<Double>();
    int frameIdx = 0;
    final int frameTotal = 10;

    private int numObj = 0;

    public static class TrackerSettings{
    	public TrackerSettings(){};
    	public TrackerSettings(boolean useKinect, boolean useSegmenter, boolean usePointClouds){
    		this.useKinect = useKinect;
    		this.useSeg = useSegmenter;
    		this.usePC = usePointClouds;
    	}
    	public boolean useKinect = false;
    	public boolean useSeg = true;
    	public boolean usePC = true;
    }
    private TrackerSettings settings;

    public Tracker(Config config_, TrackerSettings settings, SimWorld world) throws IOException{
    	this.settings = settings;

        this.world = world;
        worldState = new HashMap<Integer, Obj>();
        classyManager = new ClassifierManager(config_);

        if(settings.useKinect){
        	segmenter = new KinectSegment(config_);
        } else if(settings.useSeg){
        	segmenter = new KinectSegment(config_, world);
        } else if(settings.usePC){
        	segmenter = new SimKinectSegment(config_, world);
        } else {
        	segmenter = null;
        }

        stateLock = new Object();
        soarLock = new Object();

        soarObjects = new HashMap<Integer, Obj>();

        ros = new Ros();
        ros.connect();

        if (ros.isConnected()) {
            System.out.println("Tracker connected to rosbridge server.");
        }
        else {
            System.out.println("Tracker NOT CONNECTED TO ROSBRIDGE");
        }

        // Needs to happen after WorldModel perhaps?
        Topic soar = new Topic(ros,
                               "/rosie_soar_obj",
                               "rosie_msgs/SoarObjects");
        System.out.println("Subscribing to Soar objects!");
        soar.subscribe(new TopicCallback() {
                public void handleMessage(Message message) {
                    JsonObject jobj = message.toJsonObject();

                    synchronized(soarLock) {
                        soarObjects = new HashMap<Integer, Obj>();
                        JsonArray objArr = jobj.getJsonArray("objects");

                        for(int i = 0; i< objArr.size(); i++) {
                            ObjectData odt = new ObjectData(objArr.getJsonObject(i));
                            Obj sObj = new Obj(odt.getID());
                            double[] xyzrpy = odt.getBBoxPos();
                            sObj.setCentroid(new double[]{xyzrpy[0],
                                                          xyzrpy[1],
                                                          xyzrpy[2]});
                            sObj.setBoundingBox(new BoundingBox(odt.getBBoxDim(),
                                                                odt.getBBoxPos()));

                            for(int j=0; j<odt.numCat(); j++) {
                                CategorizedData cat = odt.getCatDat(j);
                                FeatureCategory fc =
                                    Features.getFeatureCategory(cat.getCatType());
                                Classifications cs = new Classifications();
                                for(int k=0; k<cat.numLabels(); k++)
                                    cs.add(cat.getLabel(k),
                                           cat.getConfidence(k));

                                sObj.addClassifications(fc, cs);
                            }
                            soarObjects.put(sObj.getID(), sObj);
                        }
                    }
                }
            });

        Topic pcs = new Topic(ros,
                              "/rosie_perception_commands",
                              "std_msgs/String");
        System.out.println("Subscribing to Perception commands!");
        pcs.subscribe(new TopicCallback() {
                public void handleMessage(Message message) {
                    JsonObject jobj = message.toJsonObject();
                    String command = jobj.getString("data");
                    handlePerceptionCommand(command);
                }
            });

        Topic ssc = new Topic(ros,
                               "/rosie_set_state_commands",
                               "rosie_msgs/SetStateCommand");
        ssc.subscribe(new TopicCallback() {
                public void handleMessage(Message message) {
                    JsonObject jobj = message.toJsonObject();
                    handleSetStateCommand(jobj);
                }
            });

        new TrackingThread().start();
    }

    public HashMap<Integer, Obj> getWorldState()
    {
    	synchronized(stateLock){
    		return worldState;
    	}
    }

    // Returns null if object not found
    public Obj getObject(Integer id)
    {
    	synchronized(stateLock){
    		return worldState.get(id);
    	}
    }

    public ArrayList<double[]> getPointCloud()
    {
        return segmenter.getPointCloud();
    }

    /********************************************************************
     *
     * Tracking Thread
     * This is responsible for running each frame and updating perception by:
     * 1. Getting objects from the kinect/segmenter
     * 2. Tracking those objects by assigning id's
     * 3. Adding simulated objects
     * 4. Classifying the objects
     * 5. Applying any simulated environmental dynamics
     * 6. Notifying the arm command interpreter of the new world state
     *
     ********************************************************************/

    /** Runs in the background, updating our knowledge of the scene */
    public class TrackingThread extends Thread
    {
        public void run()
        {
            while (true) {
            	Tic tic = new Tic();
            	HashMap<Integer, Obj> newWorldState = new HashMap<Integer, Obj>();

            	// 1. Get objects from the kinect/segmenter
            	ArrayList<Obj> visibleObjects = getSegmentedObjects();
            	adjustBoundingBoxes(visibleObjects);

            	// 2. Track those objects by assigning ids
           		HashMap<Obj, Integer> trackedObjects = assignObjectIds(visibleObjects);
            	for(Map.Entry<Obj, Integer> e : trackedObjects.entrySet()){
            		e.getKey().setID(e.getValue());
            		newWorldState.put(e.getValue(), e.getKey());
            	}

            	// 3. Get simulated objects
            	ArrayList<Obj> simObjects = getSimulatedObjects();
            	for(Obj obj : simObjects){
            		newWorldState.put(obj.getID(), obj);
            	}

            	// 4. Classify the objects
            	classifyObjects(newWorldState);

            	// 5. Apply Simulated Dynamics
            	simulateDynamics(newWorldState);

            	// 6. Report new info to arm interpreter
            	//synchronized(armLock){
                //armInterpreter.updateWorld(new ArrayList<Obj>(newWorldState.values()));
                //}

            	synchronized(stateLock){
            		worldState = newWorldState;
            	}

            	double frameTime = tic.toc();
            	updateFPS(frameTime);

                TimeUtil.sleep(1000/5);
            }
        }
    }

    /** Returns a list of objects that the kinect sees on the table.
     * The objects are returned as Obj's from the segmenter,
     **/
    private ArrayList<Obj> getSegmentedObjects() {
    	if(!settings.usePC){
    		// Not using point clouds, don't return any segmented objects
    		return new ArrayList<Obj>();
    	}
        return segmenter.getSegmentedObjects();
    }

    /** Returns a list of objects that are purely simulated
     *    and not extracted from a kinect point cloud (e.g. locations)
     */
    private ArrayList<Obj> getSimulatedObjects(){
    	ArrayList<Obj> simObjects = new ArrayList<Obj>();
    	synchronized(worldLock){
	    	for(SimObject so : world.objects){
	    		if(!(so instanceof SimObjectPC)){
	    			continue;
	    		}
	    		SimObjectPC obj = (SimObjectPC)so;
	    		if(obj instanceof SimFlatSurface){
	    			continue;
	    		} else if(obj instanceof SimLocation){
	    			// We always simulate locations
	    			simObjects.add(obj.getObj());
	    		} else if(!settings.usePC){
	    			// We simulate other objects if we are not using point clouds
	    			simObjects.add(obj.getObj());
	    		}
	    	}
    	}
    	return simObjects;
    }

    /** Returns a list of Obj that Soar believes exists in the world based on
     *  their most recent message. Obj have information such as their center
     *  and features they were classified with. They do not have point clouds.
     **/
    public HashMap<Integer, Obj> getSoarObjects()
    {
        synchronized(soarLock) {
            return soarObjects;
        }
    }

    public void classifyObjects(HashMap<Integer, Obj> objects){
    	for(Obj obj : objects.values()){
    		obj.addAllClassifications(classyManager.classifyAll(obj));
    	}
    }

    public void simulateDynamics(HashMap<Integer, Obj> objects){
    	synchronized(worldLock){
    		for(SimObject so : world.objects){
    			if(so instanceof ISimEffector) {
    				ISimEffector effector = (ISimEffector)so;
    				for(Obj obj : objects.values()){
    					effector.checkObject(obj);
    				}
    			}
    		}
    	}
    }

    public void updateFPS(double frameTime){
        if (frameTimes.size() < frameTotal) {
            frameTimes.add(frameTime);
        } else {
            frameTimes.set(frameIdx, frameTime);
            frameIdx = (frameIdx+1)%frameTotal;
        }

        double sum = 0;
        for (Double time: frameTimes)
        {
            sum += time;
        }
        sum /= frameTimes.size();

        fps = 1.0/sum;
    }

    
    
    /********************************************************************
     * 
     * Object Tracking
     * Code that does tracking by matching newly segmented objects
     * against those from the previous frame and those reported by Soar
     * 
     ********************************************************************/

    // This is where we do the tracking
    // This returns a mapping from objects to ids
    //   for every object given to the function
    private HashMap<Obj, Integer> assignObjectIds(ArrayList<Obj> objs){
        HashMap<Integer, Obj> soarObjects = getSoarObjects();
    	HashMap<Integer, Obj> prevObjects = new HashMap<Integer, Obj>();
    	synchronized(stateLock){
    		for(Obj obj : worldState.values()){
    			prevObjects.put(obj.getID(), obj);
    		}
    	}

    	HashMap<Obj, Integer> idMapping = new HashMap<Obj, Integer>();
    	for(Obj obj : objs){
        	idMapping.put(obj, Obj.NULL_ID);
        }

        // Match all visible objects against the previous frame
        getBestMapping(idMapping, objs, prevObjects);

        // Generate a list of soar objects that haven't been matched yet
        HashMap<Integer, Obj> unmatchedSoarObjects = (HashMap<Integer, Obj>)soarObjects.clone();

        // Generate a list of segmented objects that haven't been matched yet
        ArrayList<Obj> unmatchedObjects = new ArrayList<Obj>();

        for(Map.Entry<Obj, Integer> e : idMapping.entrySet()){
        	// We check each matched object that also matches a soar object
        	// If there is a conflict (the new object doens't overlap the soar object)
        	//   then we throw the match away.
        	Obj obj = e.getKey();
        	Integer objId = e.getValue();
        	Obj soarObj = soarObjects.get(objId);
        	if(soarObj != null){
        		if(BoundingBox.estimateIntersectionVolume(obj.getBoundingBox(), soarObj.getBoundingBox(), 8) < .000000001){
        			// Not actually a match, doesn't intersect soar object
        			e.setValue(Obj.NULL_ID);
        		} else {
        			// It is a match, remove the soar object from the unmatched list
        			unmatchedSoarObjects.remove(objId);
        		}
        	}
        	if(e.getValue() == Obj.NULL_ID){
        		unmatchedObjects.add(e.getKey());
        	}
        }

        // We match any objects not already matched to soar objects against
        // all the soar objects just to see if there is a match as well
        getBestMapping(idMapping, unmatchedObjects, unmatchedSoarObjects);

        // Any objects that still haven't been matched are given a newly generated id
        for(Map.Entry<Obj, Integer> e : idMapping.entrySet()){
        	if(e.getValue() == Obj.NULL_ID){
        		e.setValue(Obj.nextID());
        	}
        }

    	return idMapping;
    }

    private int matchObject(Obj obj, HashMap<Integer, Obj> candidates){
        // Iterate through our existing objects. If there is an object
        // sharing any single label within a fixed distance of an existing
        // object, take the existing object's ID. If the ID has already
        // been taken by another object, take a new ID
        double thresh = 0.02;
        double overlapThresh = .04;

    	double objVol = obj.getBoundingBox().volume();
    	double maxOverlapped = -1;
        int maxID = -1;

       // double minDist = Double.MAX_VALUE;
        for (Obj cand: candidates.values()) {
            double candVol = cand.getBoundingBox().volume();
            double iVol = BoundingBox.estimateIntersectionVolume(obj.getBoundingBox(), cand.getBoundingBox(), 8);
            if(iVol == 0){
            	continue;
            }

            double overlapped = iVol/objVol * iVol/candVol;
            if(overlapped > overlapThresh && overlapped >= maxOverlapped){
//            	System.out.println("== NEW BEST ==");
//            	System.out.println("  NEW VOL:   " + objVol);
//            	System.out.println("  SOAR VOL:  " + candVol);
//            	System.out.println("  INTERSXN:  " + iVol);
//            	System.out.println("  OVERLAP:   " + overlapped);
            	maxOverlapped = overlapped;
            	maxID = cand.getID();
            }
        }
        return maxID;
    }

    private void getBestMapping(HashMap<Obj, Integer> curMapping, ArrayList<Obj> objList, HashMap<Integer, Obj> candidates){
    	double OVERLAP_THRESH = .04;

    	boolean[] objAssigned = new boolean[objList.size()];
    	int i = 0;
    	for(Obj obj : objList){
    		objAssigned[i++] = false;
    	}
    	
    	ArrayList<Obj> candList = new ArrayList<Obj>();
    	boolean[] candAssigned = new boolean[candidates.size()];
    	i = 0;
    	for(Obj cand : candidates.values()){
    		candList.add(cand);
    		candAssigned[i++] = false;
    	}
    	
    	class MapScore{
    		public MapScore(int o1, int o2, double sc)
    			{ obj1 = o1;  obj2 = o2;  score = sc; }
    		
    		public Double score;
    		public int obj1;
    		public int obj2;
    	};
    	Comparator<MapScore> MapScoreComparator = new Comparator<MapScore>(){
			public int compare(MapScore arg0, MapScore arg1) {
				return arg0.score.compareTo(arg1.score);
			}
    	};
    	
    	PriorityQueue<MapScore> queue = new PriorityQueue<MapScore>(10, MapScoreComparator);
    	for(i = 0; i < objList.size(); i++){
    		Obj obj = objList.get(i);
        	double objVol = obj.getBoundingBox().volume();
    		for(int j = 0; j < candList.size(); j++){
    			Obj cand = candList.get(j);
                double candVol = cand.getBoundingBox().volume();
    			double iVol = BoundingBox.estimateIntersectionVolume(obj.getBoundingBox(), cand.getBoundingBox(), 8);	
    			double overlapped = iVol/objVol * iVol/candVol;
    			if(overlapped > OVERLAP_THRESH){
    				queue.add(new MapScore(i, j, overlapped));
    			}
    		}
    	}
    	
    	while(!queue.isEmpty()){
    		MapScore score = queue.poll();
    		if(objAssigned[score.obj1] || candAssigned[score.obj2]){
    			continue;
    		}
    		objAssigned[score.obj1] = true;
    		candAssigned[score.obj2] = true;
    		curMapping.put(objList.get(score.obj1), candList.get(score.obj2).getID());
    	}
    }

    /**************************************************************************
     * 
     * Adjust Bounding Boxes
     * This makes sure no two bounding boxes overlap vertically
     *
     *************************************************************************/
    
    private boolean verticallyAligned(double[] pt, BoundingBox bbox){
    	double[] xyz = LinAlg.resize(bbox.xyzrpy, 3);
    	double[] rpy = new double[]{bbox.xyzrpy[3], bbox.xyzrpy[4], bbox.xyzrpy[5]};
    	double[] dp = LinAlg.subtract(pt, xyz);
    	double[][] rot = LinAlg.rollPitchYawToMatrix(rpy);
    	double px = LinAlg.dotProduct(LinAlg.resize(rot[0], 3), dp);
    	double py = LinAlg.dotProduct(LinAlg.resize(rot[1], 3), dp);
    	if(Math.abs(px) < bbox.lenxyz[0]/2 && Math.abs(py) < bbox.lenxyz[1]/2){
    		return true;
    	}
    	return false;
    }
    
    private void adjustBoundingBoxes(ArrayList<Obj> objs){
        // XXX HACK HACK HACK HACK HACK HACK HACK HACK
        double fudge = 0.0010; // Just for you, James
        for (int i = 0; i < objs.size(); i++) {
        	Obj obj0 = objs.get(i);
        	Shape shape0 = obj0.getShape();
        	BoundingBox bbox0 = obj0.getBoundingBox();
            for (int j = i+1; j < objs.size(); j++) {
            	Obj obj1 = objs.get(j);
            	Shape shape1 = obj1.getShape();
            	BoundingBox bbox1 = obj1.getBoundingBox();
                if (Collisions.collision(shape0, LinAlg.xyzrpyToMatrix(bbox0.xyzrpy),
                                         shape1, LinAlg.xyzrpyToMatrix(bbox1.xyzrpy)))
                {
                	if(!verticallyAligned(LinAlg.resize(bbox0.xyzrpy, 3), bbox1) && 
                			!verticallyAligned(LinAlg.resize(bbox1.xyzrpy, 3), bbox0)){
                		continue;
                	}
                	double c0 = bbox0.xyzrpy[2];		// Center
                	double h0 = bbox0.lenxyz[2]/2;	// Height (half)

                	double c1 = bbox1.xyzrpy[2]; 	// Center
                	double h1 = bbox1.lenxyz[2]/2; 	// Height (half)

                    // Adjust zlens in bounding box based on separation
                    // between centroids. Don't forget to update shape!

                	double dz = Math.abs(c1 - c0);
                	// The adjustment is how much the bboxes needed to be backed up so the boxes are separated on z
                	double adjustment = h0 + h1 + fudge - dz;
                	if(adjustment > 0){
                		if(h0 + h1 < adjustment){
                			// Already too small to do anything about (or horizontally overlapping)
                			continue;
                		}

                		double adj0 =  h0 / (h0 + h1) * adjustment;
                		double adj1 =  h1 / (h0 + h1) * adjustment;
                		bbox0.lenxyz[2] -= adj0;
                		bbox1.lenxyz[2] -= adj1;
                		if(bbox0.xyzrpy[2] < bbox1.xyzrpy[2]){
                			bbox0.xyzrpy[2] -= adj0/2;
                			bbox1.xyzrpy[2] += adj1/2;
                		} else {
                			bbox0.xyzrpy[2] += adj0/2;
                			bbox1.xyzrpy[2] -= adj1/2;
                		}

                		obj0.setPose(bbox0.xyzrpy);
                		obj1.setPose(bbox1.xyzrpy);

                        obj0.setShape(new BoxShape(bbox0.lenxyz[0],
                                                      bbox0.lenxyz[1],
                                                      bbox0.lenxyz[2]));
                        obj1.setShape(new BoxShape(bbox1.lenxyz[0],
                                                      bbox1.lenxyz[1],
                                                      bbox1.lenxyz[2]));
                        if (Collisions.collision(obj0.getShape(), LinAlg.xyzrpyToMatrix(bbox0.xyzrpy),
                                obj1.getShape(), LinAlg.xyzrpyToMatrix(bbox1.xyzrpy))){
                        	System.out.println("STILL A COLLISION");

                        }
                	}
                }
            }
        }
        // =========== END HACK ======================
    }    



    
    //////////////////////////////////////////////////////////////
    // Methods for interacting with the classifierManager (used through guis)

    public void loadBackup(String filename) {
        try {
            classyManager.readState(filename);
        } catch (IOException e) {
            System.out.println("ERROR: Problem reading backup file.");
        }
    }

    public void clearClassificationData()
    {
        classyManager.clearData();
    }
    public void reloadClassificationData()
    {
        classyManager.reloadData();
    }
    public void undoClassification()
    {
        classyManager.undo();
    }
    public void redoClassification()
    {
        classyManager.redo();
    }
    public void addTraining(FeatureCategory category, ArrayList<Double> features, String label)
    {
        classyManager.addDataPoint(category, features, label);
    }
    public void writeClassificationState(String filename) throws IOException
    {
        classyManager.writeState(filename);
    }
    public boolean canUndoClassification()
    {
        return classyManager.hasUndo();
    }
    public boolean canRedoClassification()
    {
        return classyManager.hasRedo();
    }
    public Classifications getClassification(FeatureCategory category, Obj ob)
    {
        return classyManager.classify(category, ob);
    }

    /** Build up the object_data_t describing the observed objects
     *  in the world. Runs classifiers on the objects and builds
     *  the appropriate json to return.
     */
    public JsonArrayBuilder getObjectData()
    {
        int objCount = 0;
        JsonArrayBuilder jab = Json.createArrayBuilder();

        synchronized (stateLock) {
            for (Obj ob: worldState.values()) {
        		SimObject simObj = ob.getSourceSimObject();
            	if(simObj != null && simObj instanceof SimObjectPC && !((SimObjectPC)simObj).getVisible()){
            		continue;
        		}
            	if(!ob.isVisible()){
            		continue;
            	}

                objCount++;
                ObjectData odat = new ObjectData(ob);
                jab = jab.add(odat.toJson());
            }
            numObj = objCount;
        }

        return jab;
    }

    public int getNumObj()
    {
        synchronized (stateLock) {
            return numObj;
        }
    }

    private void handlePerceptionCommand(String command){
    	if(command.toUpperCase().contains("SAVE_CLASSIFIERS")){
    		try {
    			String[] args = command.split("=");
    			if(args.length > 1){
    				String filename = args[1];
    				classyManager.writeState(filename);
    			}
			} catch (IOException e) {
				e.printStackTrace();
			}
    	} else if(command.toUpperCase().contains("LOAD_CLASSIFIERS")){
    		try {
    			String[] args = command.split("=");
    			if(args.length > 1){
    				String filename = args[1];
    				classyManager.readState(filename);
    			}
			} catch (IOException e) {
				e.printStackTrace();
			}
    	} else if(command.toUpperCase().equals("CLEAR_CLASSIFIERS")){
    		classyManager.clearData();
    	}

    }

    // === Methods for interacting with the sensor(s) attached to the system === //
    // XXX This is kind of weird
    public ArrayList<Sensor> getSensors()
    {
    	if(settings.useSeg){
    		return segmenter.getSensors();
    	} else {
    		return new ArrayList<Sensor>();
    	}
    }

    public double[] getFloorPlane() {
        return segmenter.getFloorPlane();
    }

    // Given a command from soar to set the state for an object,
    //   sets the state if a valid command
    private void handleSetStateCommand(JsonObject setState){
     	SimObject src = null;
     	synchronized(stateLock){
     		Obj obj = worldState.get(setState.getInt("objectId"));
     		if(obj != null){
     			src = obj.getSourceSimObject();
     		}
     	}
     	if(src != null && src instanceof ISimStateful){
     		synchronized(worldLock){
     			((ISimStateful)src).setState(setState.getString("stateName"), setState.getString("stateValue"));
     		}
	 	}
     }
}

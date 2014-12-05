package probcog.sim; // XXX - Should it go here? XXX Probably not

import java.awt.Color;
import java.io.IOException;
import java.util.*;

import probcog.perception.Obj;
import april.jmat.*;
import april.sim.*;
import april.util.*;
import april.vis.*;


public class SimBlocker extends SimLocation implements ISimEffector
{
	protected double storedZ = 0;
	
    public SimBlocker(SimWorld sw) {
		super(sw);
	}

	@Override
	public void checkObject(Obj obj) {
		if(!currentState.containsKey("enabled") || currentState.get("enabled").equals("false")){
			return;
		}
		double[] diff = LinAlg.subtract(xyzrpy, obj.getPose());
		double dx = Math.abs(diff[0]);
		double dy = Math.abs(diff[1]);
		double[] scaledDims = getScaledDims();
		if(dx < scaledDims[0]/2 && dy < scaledDims[1]/2){
			obj.setVisible(false);
		}
	}

	@Override
    public Shape getShape()
    {
    	return new BoxShape(0, 0, 0);
    }

    public VisObject getVisObject()
    {
        ArrayList<Object> objs = new ArrayList<Object>();
        double[] dims = getScaledDims();
        objs.add(LinAlg.scale(dims[0], dims[1], dims[2]));
    	if(currentState.containsKey("enabled") && currentState.get("enabled").equals("true")){
    		objs.add(new VzBox(new VzMesh.Style(new Color(0, 0, 0, 0)), new VzLines.Style(Color.black, 2)));
//            objs.add(new VzBox(new VzMesh.Style(color)));
    	} else {
    		objs.add(new VzBox(new VzMesh.Style(new Color(0, 0, 0, 0)), new VzLines.Style(Color.black, 2)));
    	}

        return new VisChain(objs.toArray());
    }
    
    @Override
    public void setState(String stateName, String stateVal) {
    	if(stateName.equals("enabled") && stateVal.equals("false")){
    		double[] pos = LinAlg.matrixToXyzrpy(this.getPose());
    		pos[2] = storedZ - scale * lenxyz[2];
    		setPose(LinAlg.xyzrpyToMatrix(pos));
    	} else if(stateName.equals("enabled") && stateVal.equals("true")){
    		double[] pos = LinAlg.matrixToXyzrpy(this.getPose());
    		pos[2] = storedZ;
    		setPose(LinAlg.xyzrpyToMatrix(pos));
    	}
    	super.setState(stateName, stateVal);
    }
    
	/** Restore state that was previously written **/
	@Override
	public void read(StructureReader ins) throws IOException
	{
		super.read(ins);
		
		lenxyz = ins.readDoubles();
		storedZ = xyzrpy[2];
		
		if(!currentState.containsKey("enabled")){
			this.addNewState("enabled", new String[]{"false", "true"});
		}

		this.setState("enabled", this.getState("enabled"));
	}
	
	@Override
    public void write(StructureWriter outs) throws IOException
    {
		super.write(outs);
		outs.writeDoubles(lenxyz);
    }
}

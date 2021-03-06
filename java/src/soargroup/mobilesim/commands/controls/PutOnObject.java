package soargroup.mobilesim.commands.controls;

import java.util.*;

import soargroup.mobilesim.commands.*;
import soargroup.mobilesim.lcmtypes.diff_drive_t;

// XXX Temporary port to new control law implementation. This is just a water-
// through-the-pipes implementation.
public class PutOnObject implements ControlLaw
{
    Params storedParams = Params.makeParams();

    /** Strictly for creating instances for parameter checks */
    public PutOnObject()
    {
    }

    public PutOnObject(Map<String, TypedValue> parameters)
    {
        assert (parameters.containsKey("object-id"));
        System.out.println("PUT ON OBJECT: " + parameters.get("object-id").getInt().toString());

		if(!parameters.containsKey("relation")){
			parameters.put("relation", new TypedValue("on"));
		}
    }

    /** Start/stop the execution of the control law.
     *
     *  @param run  True causes the control law to begin execution, false stops it
     **/
    public void setRunning(boolean run)
    {
       
    }

    /** Get the name of this control law. Mostly useful for debugging purposes.
     *
     *  @return The name of the control law
     **/
    public String getName()
    {
        return "PUT_ON_OBJECT";
    }

    /** Get the parameters that can be set for this control law.
     *
     *  @return An iterable collection of all possible parameters
     **/
    public Collection<TypedParameter> getParameters()
    {
        // No parameters, so this can just return an empty container
    	ArrayList<TypedParameter> params = new ArrayList<TypedParameter>();
    	params.add(new TypedParameter("object-id", TypedValue.TYPE_INT, true));

    	params.add(new TypedParameter("relation", TypedValue.TYPE_STRING, false));
    	return params;
    }

	@Override
	public diff_drive_t drive(DriveParams params) {
		return null;
	}
}

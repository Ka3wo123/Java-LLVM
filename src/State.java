import java.util.LinkedHashMap;
import java.util.Map;

public class State {
    private Map<String, IdInfo> ids;
    private int regCounter;
    private Statement[] statements;

    class IdInfo {
        private String register;
        private String type;

        IdInfo(String register, String type) {
            this.register = register;
            this.type = type;
        }

        public String getRegister() {
            return register;
        }

        public String getType() {
            return type;
        }
    }

    /**
     * Nested class Statement for managing labels.
     */
    class Statement {
        private int counter;
        private String[] labels;

        Statement(String[] labels) {
            this.labels = labels;
            this.counter = 0;
        }

        public String[] getLabels() {
            int len = this.labels.length;
            String[] rv = new String[len];
            for (int i = 0; i < len; i++) {
                rv[i] = this.labels[i] + "_" + this.counter;
            }
            this.counter++;
            return rv;
        }

        public void resetCounter() {
            this.counter = 0;
        }
    }

    // Map all kinds of labels to be used to a code
    private static final Map<String, Integer> labelTypes = new LinkedHashMap<String, Integer>() {
        private static final long serialVersionUID = 1L;
        {
            put("if", 0);
            put("while", 1);
            put("oob", 2);
            put("and", 3);
        }
    };

    // Constructor: initialize identifier map and counters
    public State() {
        this.ids = new LinkedHashMap<>();
        this.regCounter = 0;
        this.statements = new Statement[State.labelTypes.size()];
        this.statements[0] = new Statement(new String[]{"if", "else", "fi"});
        this.statements[1] = new Statement(new String[]{"while", "do", "done"});
        this.statements[2] = new Statement(new String[]{"outOfBounds", "withinBounds"});
        this.statements[3] = new Statement(new String[]{"true", "false", "end"});
    }

    /**
     * Return next register available.
     */
    public String newReg() {
        return "%_" + this.regCounter++;
    }

    /**
     * Associate an identifier with the register holding the address of the identifier.
     */
    public String newReg(String id, String llvmType) {
        this.ids.put(id, new IdInfo("%_" + this.regCounter, llvmType));
        return this.newReg();
    }

    /**
     * Insert information about a new identifier used by this method.
     */
    public void put(String id, String register, String llvmType) {
        this.ids.put(id, new IdInfo(register, llvmType));
    }

    /**
     * Get a new mutable version of all information about an identifier.
     */
    public IdInfo getIdInfo(String id) {
        return this.ids.get(id);
    }

    /**
     * Get the current register counter value.
     */
    public int getRegCounter() {
        return this.regCounter;
    }

    /**
     * Get a new Statement of the type requested.
     */
    public String[] newLabel(String label) {
        Integer index = State.labelTypes.get(label);
        if (index == null) {
            return null;
        }
        return this.statements[index].getLabels();
    }

    /**
     * Reset state.
     */
    public void clear() {
        this.ids.clear();
        this.regCounter = 0;
        for (Statement statement : this.statements) {
            statement.resetCounter();
        }
    }
}

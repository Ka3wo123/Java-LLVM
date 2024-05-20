import java.util.*;
import javafx.util.Pair;

@interface CaseOfMessageSendOnly{};
public class FirstVisitor extends JavaParserBaseVisitor<String>{

    protected Map <String, ClassData> classes;
    protected Map <String, String> vars;
    protected LinkedList<String> messageQueue;
    private Integer nextVar, nextMethod;
    private String className;

    public FirstVisitor(){
        this.classes = new LinkedHashMap<>();
        this.messageQueue = new LinkedList<>();
        this.vars = new LinkedHashMap<>();
        this.nextVar = ClassData.pointerSize;
        this.nextMethod = 0;
    }

    @CaseOfMessageSendOnly
    /*  MainClass
        class f1 -> Identifier(){
            public static void main(String[] f11 -> Identifier()){
                f14 -> ( VarDeclaration() )*
                f15 -> ( Statement() )*
        } */
    public String visit(JavaParser.MainClassContext node, ClassData data){

        this.className = node.identifier(0).accept(this);
        ClassData cd = new ClassData(null);
        MethodData md = new MethodData(this.className, "void", 0, null);
        node.varDeclaration(0).accept(this);
        node.statement(0).accept(this); // info about MessageSend need to be collected on this pass

        this.classes.put(this.className, cd);
        return null;
    }

    /* ClassDeclaration
    class f1 -> Identifier(){
        f3 -> ( VarDeclaration() )*
        f4 -> ( MethodDeclaration() )*
    }
    */
    public String visit(JavaParser.ClassDeclarationContext node, ClassData data){
        String id = node.identifier().accept(this);
        ClassData cd = new ClassData(null);
        this.className = id;

        /* initialize offsets */
        this.nextVar = ClassData.pointerSize;
        this.nextMethod = 0;

        /* pass ClassData to each field */
        node.varDeclaration(0).accept(this);

        /* pass ClassData to each member method */
        node.methodDeclaration(0).accept(this);

        /* enter class data collected in the symbol table */
        cd.setSize();
        this.classes.put(id, cd);
        return null;
    }

    /*
        class f1 -> Identifier() f2 -> "extends" f3 -> Identifier(){}
            f5 -> ( VarDeclaration() )*
            f6 -> ( MethodDeclaration() )*
        }
    */
    public String visit(JavaParser.ClassExtendsDeclarationContext node, ClassData data){
        String id = node.identifier(0).accept(this), parent = node.identifier(0).accept(this);
        this.className = id;

        /* Pass a meta data object down to the declarations sections, derived class inherits all fields and methods */
        ClassData cd = new ClassData(parent), cdParent = this.classes.get(parent);
        cd.vars.putAll(cdParent.vars);
        cd.methods.putAll(cdParent.methods);
        this.nextVar = cdParent.getOffsetOfNextVar();
        this.nextMethod = cd.methods.size();

        /* pass ClassData to each field */
        node.varDeclaration(0).accept(this);

        /* pass ClassData to each member method */
        node.methodDeclaration(0).accept(this);

        /* enter class data collected in the symbol table */
        cd.setSize();
        this.classes.put(id, cd);
        return null;
    }

    /*  VarDeclaration
        f0 -> Type()
        f1 -> Identifier()
    bind each variable name/id to a type*/
    public String visit(JavaParser.VarDeclarationContext node, ClassData data){
        String type = node.type().accept(this);
        String id = node.identifier().accept(this);

        this.vars.put(id, type);

        /* store the variable and calculate the exact memory address for the next one to be stored */
        Pair<String, Integer> pair = new Pair<String, Integer>(type, this.nextVar);

        /* if it is not about a variable declared in a method, but in a class, update lookup Table */
        if(data != null){
            data.vars.put(id, pair);
            this.nextVar += ClassData.getSize(type).getKey();
        }
        return null;
    }

    /*  MethodDeclaration
        public f1 -> Type() f2 -> Identifier() (f4 -> ( FormalParameterList() )?){
            f7 -> ( VarDeclaration() )*
            f8 -> ( Statement() )*
            return f10 -> Expression();
        }
     */
    public String visit(JavaParser.MethodDeclarationContext node, ClassData data){
        String type = node.type().accept(this);
        String id = node.identifier().accept(this);

        /* get argument types, if they exist */
        ArrayList<Pair<String, String>> args = null;
        if (!node.formalParameterList().isEmpty())
            args = MyUtils.getParams(node.formalParameterList().accept(this).split(","));

        /* visit both all statement nodes and the return statement expression in order to detect any messages send*/
        node.statement(0).accept(this);
        node.expression().accept(this);


        /* if method already exists, override it by defining this class as the last to implement it
           other fields like return type or arguments do not need to be update it, mini-java does not support parametric polymorphism*/
        if(data.methods.containsKey(id))
            data.methods.put(id, new MethodData(this.className, type, data.methods.get(id).offset, args));
        else
            data.methods.put(id, new MethodData(this.className, type, this.nextMethod++, args));
        return null;
    }

    /* FormalParameterList: f0 -> FormalParameter() f1 -> FormalParameterTail() Get all parameter types a String*/
    public String visit(JavaParser.FormalParameterListContext node, ClassData data){
        String head = node.formalParameter().accept(this), tail = node.formalParameterTail().accept(this);
        return head + tail;
    }

    /* FormalParameter f0 -> Type() f1 -> Identifier() Returns just the parameter type as a String*/
    public String visit(JavaParser.FormalParameterContext node, ClassData data){
        String type = node.type().accept(this), id = node.identifier().accept(this);
        this.vars.put(id, type);
        return type + ":" + id;
    }

    /* FormalParameterTail f0 -> ( FormalParameterTerm)* */
    public String visit(JavaParser.FormalParameterTailContext node, ClassData data){
        String retval = "";
        for (int i = 0; i < node.formalParameterTerm().size(); i++)
            retval += node.formalParameterTerm().get(i).accept(this);
        return retval;
    }

    /* FormalParameterTerm: ,f1 -> FormalParameter */
    public String visit(JavaParser.FormalParameterTermContext node, ClassData data){
        return "," + node.formalParameter().accept(this);
    }

    /* Type: f0 -> ArrayType() | BooleanType() | IntegerType() | Identifier() */
    public String visit(JavaParser.TypeContext node, ClassData data){
        int which = node.getRuleIndex();
        if (which == 0)
            return "array";
        else if(which == 1)
            return "boolean";
        else if(which == 2)
            return "integer";
        else
            return node.identifier().accept(this);  // identifier
    }

    /* Identifier f0: return the id as a string*/
    public String visit(JavaParser.IdentifierContext node, ClassData data){
        return node.IDENTIFIER().toString();
    }

    @CaseOfMessageSendOnly
    /*  AssignmentStatement:   f0 -> Identifier() = f2 -> Expression(); */
    public String visit(JavaParser.AssignmentStatementContext node, ClassData data){
        String left = node.identifier().accept(this), right = node.expression().accept(this);

        /* in case of an assignment, a parent class reference might now point to a child class object,
           so adjust the variables table to reflect that, in order for the right method to be executed in case of a message send*/
        if(right != null)
            this.vars.put(left, right);

        return null;
    }

    @CaseOfMessageSendOnly
    /*MessageSend
     * f0 -> PrimaryExpression().f2 -> Identifier()(f4 -> ( ExpressionList() )?) */
    public String visit(JavaParser.MessageSendContext node, ClassData data){
        String className = node.primaryExpression().accept(this);
        this.messageQueue.addLast(className);
        node.expressionList().accept(this); // visit ExpressionList to record MessageSends in there as well
        return className;           // return className because this node might be an inner/nested MessageSend
    }

    @CaseOfMessageSendOnly
    /*PrimaryExpression
    * f0 -> IntegerLiteral() | TrueLiteral() | FalseLiteral() | Identifier()
    | ThisExpression() | ArrayAllocationExpression() | AllocationExpression() | BracketExpression() */
    public String visit(JavaParser.PrimaryExpressionContext node, ClassData data){
        int childNode = node.integerLiteral().getRuleIndex();

        // in case of an identifier, return the corresponding data type
        if(childNode == 3)
            return this.vars.get(node.integerLiteral().accept(this));
            // in case of a 'this' pointer, the name of this class is the appropriate data type
        else if(childNode == 4)
            return this.className;
            // in case of an allocation or a bracket expression, actual data type will be passed up
        else if(childNode > 5)
            return node.integerLiteral().accept(this);
        else
            return null;
    }

    @CaseOfMessageSendOnly
    /*AllocationExpresion:  new f1 -> Identifier()() */
    public String visit(JavaParser.AllocationExpressionContext node, ClassData data){
        return node.identifier().accept(this);  //return class name of new instance
    }

    @CaseOfMessageSendOnly
    /*BracketExpression:    ( f1 -> Expression() )*/
    public String visit(JavaParser.BracketExpressionContext node, ClassData data){
        return node.expression().accept(this);
    }
}
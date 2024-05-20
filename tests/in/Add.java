class Add {
    public static void main(String[] args) {
        Calc calc;
        calc = new Calc(5);
        int result = calc.twice();
        System.out.println(result);
    }
}

class Calc {
    private int a;

    Calc(int a) {
        this.a = a;
    }

    int twice() {
        return this.a * 2;
    }
}

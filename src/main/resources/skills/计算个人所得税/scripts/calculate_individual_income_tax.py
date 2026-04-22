# 原始参数对象由后端注入到 args
salary = float(args.get("salary", 0))
insurance = float(args.get("insurance", 0))
special_deduction = float(args.get("special_deduction", 0))
other_deduction = float(args.get("other_deduction", 0))
basic_deduction = 5000.0

taxable_income = salary - insurance - special_deduction - other_deduction - basic_deduction

if taxable_income <= 0:
    result = {
        "taxable_income": round(taxable_income, 2),
        "tax_rate": 0.0,
        "quick_deduction": 0.0,
        "tax": 0.0
    }
else:
    brackets = [
        (3000.0, 0.03, 0.0),
        (12000.0, 0.10, 210.0),
        (25000.0, 0.20, 1410.0),
        (35000.0, 0.25, 2660.0),
        (55000.0, 0.30, 4410.0),
        (80000.0, 0.35, 7160.0),
        (float("inf"), 0.45, 15160.0),
    ]

    tax_rate = 0.0
    quick_deduction = 0.0
    for upper, rate, deduction in brackets:
        if taxable_income <= upper:
            tax_rate = rate
            quick_deduction = deduction
            break

    tax = taxable_income * tax_rate - quick_deduction
    result = {
        "taxable_income": round(taxable_income, 2),
        "tax_rate": tax_rate,
        "quick_deduction": quick_deduction,
        "tax": round(max(tax, 0.0), 2)
    }

result

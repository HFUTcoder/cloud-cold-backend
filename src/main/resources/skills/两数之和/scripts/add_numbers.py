# 原始参数对象由后端注入到 args
nums = args.get("nums")
a = args.get("a")
b = args.get("b")

if isinstance(nums, list):
    if a is None and len(nums) > 0:
        a = nums[0]
    if b is None and len(nums) > 1:
        b = nums[1]

result = a + b
result

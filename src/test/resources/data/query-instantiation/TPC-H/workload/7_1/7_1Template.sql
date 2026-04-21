-- cannotFindArgs:{id:105,data:'ETHIOPIA',operand:public.nation.n_name},{id:106,data:'INDIA',operand:public.nation.n_name},{id:101,data:'INDIA',operand:public.nation.n_name},{id:102,data:'ETHIOPIA',operand:public.nation.n_name}
select supp_nation, cust_nation, l_year, sum(volume) as revenue
from (
	select n1.n_name as supp_nation, n2.n_name as cust_nation, extract(year from l_shipdate) as l_year
		, l_extendedprice * (1 - l_discount) as volume
	from supplier, lineitem, orders, customer, nation n1, nation n2
	where s_suppkey = l_suppkey
		and o_orderkey = l_orderkey
		and c_custkey = o_custkey
		and s_nationkey = n1.n_nationkey
		and c_nationkey = n2.n_nationkey
		and ((n1.n_name = 'ETHIOPIA'
				and n2.n_name = 'INDIA')
			or (n1.n_name = 'INDIA'
				and n2.n_name = 'ETHIOPIA'))
		and l_shipdate between date 'Mirage#103' and date 'Mirage#104'
) shipping
group by supp_nation, cust_nation, l_year
order by supp_nation, cust_nation, l_year;
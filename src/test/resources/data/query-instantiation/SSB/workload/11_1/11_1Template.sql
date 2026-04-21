select d_year, c_nation, sum(lo_revenue - lo_supplycost) as profit
from dates, customer, supplier, part, lineorder
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_partkey = p_partkey
	and lo_orderdate = d_datekey
	and c_region = 'Mirage#9'
	and s_region = 'Mirage#10'
	and (p_mfgr = 'Mirage#11'
		or p_mfgr = 'Mirage#12')
group by d_year, c_nation
order by d_year, c_nation;
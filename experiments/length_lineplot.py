import psycopg2 as psycopg2
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt

sns.set_theme(style="whitegrid")
img_path = '/Users/tim/master-thesis/latex/img/experiments/'
#sns.set(rc = {'figure.figsize':(11,8)})
algorithms_order = ['Seeded DynaMOSA', 'DynaMOSA',  'Random Search']

with psycopg2.connect("dbname=rustyunit user=rust password=Lz780231Ray") as conn:
    sql_random = "select * from experiments_random;"
    random_data = pd.read_sql_query(sql_random, conn)
    random_data['Algorithm'] = 'Random Search'
    random_data = random_data[random_data['crate'] != 'toycrate']

    sql_seeded_dynamosa = "select * from experiments_seeded_dynamosa;"
    seeded_dynamosa_data = pd.read_sql_query(sql_seeded_dynamosa, conn)
    seeded_dynamosa_data['Algorithm'] = 'Seeded DynaMOSA'
    seeded_dynamosa_data = seeded_dynamosa_data[seeded_dynamosa_data['crate'] != 'toycrate']

    sql_dynamosa = "select * from experiments_dynamosa;"
    dynamosa_data = pd.read_sql_query(sql_dynamosa, conn)
    dynamosa_data['Algorithm'] = 'DynaMOSA'
    seeded_dynamosa_data = seeded_dynamosa_data[seeded_dynamosa_data['crate'] != 'toycrate']

    data = pd.concat([random_data, seeded_dynamosa_data, dynamosa_data])

    plot_data = data.groupby(['Algorithm', 'gen']).mean()
    fig = plt.figure(1)
    # mir_coverage, tests_length, tests, covered_targets

    coverage_plot = sns.lineplot(x="gen", y="tests_length",
                 hue="Algorithm",# style="event",
                 data=plot_data, hue_order=algorithms_order)
    coverage_plot.get_legend().set_title(None)
    coverage_plot.set(xlabel = "Generation", ylabel = "Test case length")
    plt.show()
    fig.savefig(img_path + 'length-over-time-crates.png', dpi=300, format='png', bbox_inches='tight')